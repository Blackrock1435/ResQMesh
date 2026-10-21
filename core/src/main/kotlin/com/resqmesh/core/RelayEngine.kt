package com.resqmesh.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

sealed class SendResult {
    data class Ok(val id: String) : SendResult()
    data class Err(val reason: String) : SendResult()
}

/**
 * Store-and-forward controlled-flooding engine. Independent of UI and radio.
 * All entry points are serialized by one lock; duplicate processing is additionally prevented by the
 * store's atomic insertIfNew (so it stays correct even if two engines share a database).
 *
 * "link" = a transport-level connection (e.g. a Nearby endpointId). Stable neighbour identity (deviceId)
 * is learned from a signed HELLO that arrives with hop == 0.
 *
 * Acknowledgment semantics:
 *  - SENT      : this node handed the packet to a neighbour's radio link. Says nothing about delivery.
 *  - DELIVERED : a signed RECEIPT from the intended destination (DIRECT: the dest; GROUP: a key-holder
 *                proving possession; SOS: any device, listed per device) reached the originator.
 *  A relay never produces a DELIVERED receipt.
 */
class RelayEngine(
    private val identity: Identity,
    private val displayName: () -> String,
    private val store: MeshStore,
    private val transport: Transport,
    private val groups: GroupKeys,
    private val listener: EngineListener = object : EngineListener {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cfg: EngineConfig = EngineConfig(),
) {
    val stats = Stats()
    val deviceId: String get() = identity.deviceId
    private val lock = Any()

    private class LinkState(var deviceId: String? = null, var tokens: Double, var lastRefill: Long, val connectedAt: Long = lastRefill, var lastSync: Long = lastRefill)
    private val links = LinkedHashMap<String, LinkState>()

    // ---------------------------------------------------------------- link events

    fun onLinkUp(link: String) = synchronized(lock) {
        links[link] = LinkState(null, cfg.rateBurst, clock())
        sendHelloOwn()
        sendInventory(link)
        flushPending(ignoreBackoff = true)
    }

    fun onLinkDown(link: String) = synchronized(lock) {
        links.remove(link)
        listener.onPeers()
    }

    /** Device ids of currently linked, identified neighbours. */
    fun connectedDeviceIds(): Set<String> = synchronized(lock) { links.values.mapNotNull { it.deviceId }.toSet() }
    fun linkCount(): Int = synchronized(lock) { links.size }
    fun deviceIdForLink(link: String): String? = synchronized(lock) { links[link]?.deviceId }

    // ---------------------------------------------------------------- receive path

    fun onBytes(link: String, data: ByteArray) = synchronized(lock) { receive(link, data) }

    private fun receive(link: String, data: ByteArray) {
        val now = clock()
        val ls = links.getOrPut(link) { LinkState(null, cfg.rateBurst, now) }
        ls.tokens = minOf(cfg.rateBurst, ls.tokens + (now - ls.lastRefill) / 1000.0 * cfg.ratePerSec)
        ls.lastRefill = now
        if (ls.tokens < 1.0) { stats.rateLimited++; return }
        ls.tokens -= 1.0

        val p = try { PacketCodec.decode(data) } catch (e: PacketException) {
            stats.malformed++; listener.log("malformed packet: ${e.message}"); return
        }
        if (!PacketCodec.verify(p)) { stats.invalid++; listener.log("invalid identity/signature"); return }
        if (p.createdAtMs > now + cfg.maxSkewMs || now > p.createdAtMs + p.ttlSec * 1000L + cfg.maxSkewMs) {
            stats.expired++; listener.log("stale/future packet rejected"); return
        }
        when (p.type) {
            PacketType.INVENTORY -> onInventory(link, p)
            PacketType.REQUEST -> onRequest(link, p)
            else -> onStorable(link, ls, p, data, now)
        }
    }

    private fun onStorable(link: String, ls: LinkState, p: Packet, raw: ByteArray, now: Long) {
        val expires = now + p.ttlSec * 1000L // local clock + ttl: sender's clock is never trusted for expiry
        val base = StoredPacket(
            id = p.id, type = p.type, senderId = p.senderId, destId = p.destId, groupId = p.groupId,
            createdAtMs = p.createdAtMs, receivedAtMs = now, expiresAtMs = expires, hop = p.hop, maxHop = p.maxHop,
            priority = PacketType.priority(p.type), own = p.senderId == identity.deviceId,
            relay = true, raw = raw, status = Status.RECEIVED,
        )
        if (!store.insertIfNew(base)) { stats.duplicates++; return }
        stats.received++; stats.lastTransferMs = now
        var rec = base
        when (p.type) {
            PacketType.HELLO -> {
                val name = String(p.payload, Charsets.UTF_8).filter { !it.isISOControl() }.take(Limits.MAX_NAME_CHARS)
                store.upsertPeer(PeerRecord(p.senderId, name, p.senderPubKey, now))
                if (p.hop == 0) ls.deviceId = p.senderId
                listener.onPeers()
            }
            PacketType.DIRECT -> if (p.destId == identity.deviceId) {
                val plain = CryptoUtil.decryptFrom(identity, aad(p), p.payload)
                val inner = plain?.let { Inner.parse(it) }
                if (inner == null) { stats.invalid++; rec = rec.copy(relay = false, status = Status.CANCELLED) }
                else { rec = deliver(rec, inner); sendReceipt(p, null) }
                rec = rec.copy(relay = false); store.update(rec)
            }
            PacketType.GROUP -> {
                val key = p.groupId?.let { groups.key(it) }
                if (key != null) {
                    val inner = CryptoUtil.decryptGroup(key, aad(p), p.payload)?.let { Inner.parse(it) }
                    if (inner != null) {
                        rec = deliver(rec, inner)
                        sendReceipt(p, CryptoUtil.groupReceiptProof(key, p.id, identity.deviceId))
                    } else stats.invalid++
                }
            }
            PacketType.BROADCAST -> Inner.parse(p.payload)?.let { rec = deliver(rec, it) } ?: run { stats.invalid++ }
            PacketType.SOS -> {
                val inner = Inner.parse(p.payload)
                if (inner != null) { rec = deliver(rec, inner); listener.onSos(rec); sendReceipt(p, null) } else stats.invalid++
            }
            PacketType.SOS_RESOLVE -> {
                val target = runCatching { PacketCodec.hex(p.payload) }.getOrNull()
                val sos = target?.let { store.get(it) }
                if (sos != null && sos.type == PacketType.SOS && sos.senderId == p.senderId) {
                    store.update(sos.copy(status = Status.RESOLVED, relay = false)); listener.onStatus(sos.id)
                }
            }
            PacketType.RECEIPT -> if (p.destId == identity.deviceId) {
                applyReceipt(p, now); rec = rec.copy(relay = false); store.update(rec)
            }
        }
        if (p.destId == identity.deviceId && p.type == PacketType.DIRECT) return
        if (rec.relay && p.hop < p.maxHop) forward(rec, exclude = link)
    }

    private fun deliver(rec: StoredPacket, inner: Inner.Parsed): StoredPacket {
        val l = inner.loc
        val r = rec.copy(text = inner.text, lat = l?.lat, lon = l?.lon, accM = l?.accuracyM, fixTimeMs = l?.fixTimeMs)
        store.update(r); listener.onMessage(r); return r
    }

    private fun aad(p: Packet) = "${p.id}|${p.senderId}|${p.destId ?: ""}|${p.groupId ?: ""}".toByteArray()

    // ---------------------------------------------------------------- receipts

    private fun sendReceipt(orig: Packet, proof: ByteArray?) {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.write(PacketCodec.unhex(orig.id)); o.writeByte(ReceiptKind.DELIVERED)
        val pr = proof ?: ByteArray(0)
        o.writeByte(pr.size); o.write(pr)
        originate(PacketType.RECEIPT, orig.senderId, null, bo.toByteArray(), cfg.receiptTtlSec, cfg.defaultMaxHop, Status.PENDING)
    }

    private fun applyReceipt(p: Packet, now: Long) {
        try {
            val i = DataInputStream(p.payload.inputStream())
            val id = PacketCodec.hex(ByteArray(16).also { i.readFully(it) })
            val kind = i.readUnsignedByte()
            val proof = ByteArray(i.readUnsignedByte()).also { i.readFully(it) }
            val orig = store.get(id) ?: return
            if (!orig.own || kind != ReceiptKind.DELIVERED) return
            when (orig.type) {
                PacketType.DIRECT -> if (p.senderId != orig.destId) return
                PacketType.GROUP -> {
                    val key = orig.groupId?.let { groups.key(it) } ?: return
                    if (!CryptoUtil.constantTimeEquals(proof, CryptoUtil.groupReceiptProof(key, id, p.senderId))) { stats.invalid++; return }
                }
                PacketType.SOS -> {}
                else -> return
            }
            if (!store.addReceipt(ReceiptRecord(id, p.senderId, kind, now))) return
            if (orig.type == PacketType.DIRECT) store.update(orig.copy(status = Status.DELIVERED, relay = false))
            listener.onStatus(id)
        } catch (e: Exception) { stats.malformed++ }
    }

    // ---------------------------------------------------------------- inventory sync

    private fun sendHelloOwn() {
        originate(PacketType.HELLO, null, null, displayName().take(Limits.MAX_NAME_CHARS).toByteArray(), cfg.helloTtlSec, 6, Status.SENT)
    }

    private fun sendInventory(link: String) {
        val ids = store.inventory(clock(), cfg.inventoryMax)
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.writeShort(ids.size); ids.forEach { o.write(PacketCodec.unhex(it)) }
        sendLinkLocal(link, PacketType.INVENTORY, bo.toByteArray())
    }

    private fun sendLinkLocal(link: String, type: Int, payload: ByteArray) {
        val p = sign(Packet(Limits.VERSION, type, CryptoUtil.randomId(), identity.deviceId, null, null, 60, clock(), 0, 0,
            identity.publicKeyBytes, payload, ByteArray(0)))
        transport.send(link, PacketCodec.encode(p))
    }

    private fun parseIds(payload: ByteArray, max: Int): List<String>? = try {
        val i = DataInputStream(payload.inputStream())
        val n = i.readUnsignedShort()
        if (n > max || i.available() != n * 16) null else List(n) { PacketCodec.hex(ByteArray(16).also { b -> i.readFully(b) }) }
    } catch (e: Exception) { null }

    private fun onInventory(link: String, p: Packet) {
        val ids = parseIds(p.payload, cfg.inventoryMax) ?: run { stats.malformed++; return }
        val key = links[link]?.deviceId ?: link
        val missing = ArrayList<String>()
        for (id in ids) {
            val mine = store.get(id)
            if (mine == null) { if (missing.size < cfg.requestMax) missing += id; continue }
            // neighbour already holds it: never push it again; an own packet is at least with a neighbour now
            val sent = mine.sentTo.split(',').filter { it.isNotEmpty() }.toSet()
            if (key !in sent && mine.raw != null) {
                val st = if (mine.own && mine.status == Status.PENDING) Status.SENT else mine.status
                store.update(mine.copy(sentTo = (sent + key).joinToString(","), status = st))
                if (st != mine.status) listener.onStatus(id)
            }
        }
        if (missing.isEmpty()) return
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        o.writeShort(missing.size); missing.forEach { o.write(PacketCodec.unhex(it)) }
        sendLinkLocal(link, PacketType.REQUEST, bo.toByteArray())
    }

    private fun onRequest(link: String, p: Packet) {
        val ids = parseIds(p.payload, cfg.requestMax) ?: run { stats.malformed++; return }
        val now = clock()
        val key = links[link]?.deviceId ?: link
        val recs = ids.mapNotNull { store.get(it) }.sortedBy { it.priority } // emergency packets first
        for (r in recs) {
            if (r.raw == null || r.expiresAtMs <= now || !r.relay) continue
            if (!r.own && (r.hop >= r.maxHop || r.senderId == links[link]?.deviceId)) continue
            if (transport.send(link, encodeForSend(r))) {
                stats.relayed++; stats.lastTransferMs = now
                val sent = r.sentTo.split(',').filter { it.isNotEmpty() }.toSet() + key
                val st = if (r.own && r.status == Status.PENDING) Status.SENT else r.status
                store.update(r.copy(sentTo = sent.joinToString(","), status = st))
                if (st != r.status) listener.onStatus(r.id)
            }
        }
    }

    // ---------------------------------------------------------------- forwarding / retry

    private fun sign(p: Packet) = p.copy(signature = identity.sign(PacketCodec.signingBytes(p)))

    private fun encodeForSend(r: StoredPacket): ByteArray {
        val p = PacketCodec.decode(r.raw!!)
        return PacketCodec.encode(p.copy(hop = if (r.own) r.hop else r.hop + 1))
    }

    /** @return true if at least one neighbour was served or there was nobody left to serve. */
    private fun forward(rec0: StoredPacket, exclude: String?): Boolean {
        var rec = rec0
        if (rec.raw == null) return true
        if (!rec.own && rec.hop >= rec.maxHop) return true
        val sent = rec.sentTo.split(',').filter { it.isNotEmpty() }.toMutableSet()
        var anyNew = false; var anyFail = false; var eligible = 0
        val bytes by lazy { encodeForSend(rec0) }
        for ((link, st) in links) {
            if (link == exclude) continue
            if (!rec.own && st.deviceId != null && st.deviceId == rec.senderId) continue
            // packets that existed before this link came up are exchanged by inventory/request (pull), not blind push
            if (!rec.own && rec.receivedAtMs < st.connectedAt) continue
            val key = st.deviceId ?: link
            if (key in sent) continue
            eligible++
            if (transport.send(link, bytes)) {
                sent += key; anyNew = true
                if (!rec.own) stats.relayed++
                stats.lastTransferMs = clock()
            } else anyFail = true
        }
        if (anyNew) {
            rec = rec.copy(sentTo = sent.joinToString(","), status = if (rec.own && rec.status == Status.PENDING) Status.SENT else rec.status)
            store.update(rec)
            if (rec.own) listener.onStatus(rec.id)
        }
        return eligible == 0 || anyNew || !anyFail
    }

    /** Called on a timer (~every 5 s) and on link-up. */
    fun tick() = synchronized(lock) {
        val now = clock()
        val exp = store.expire(now)
        if (exp.isNotEmpty()) { stats.expired += exp.size; exp.forEach { listener.onStatus(it) } }
        store.purgeOlderThan(now - cfg.tombstoneMs)
        store.enforceBound(cfg.maxStored)
        for ((link, st) in links) if (now - st.lastSync >= cfg.syncMs) { st.lastSync = now; sendInventory(link) }
        flushPending(ignoreBackoff = false)
    }

    private fun flushPending(ignoreBackoff: Boolean) {
        val now = clock()
        for (rec in store.forwardCandidates(now, ignoreBackoff, 200)) forwardOrCount(rec, now)
    }

    private fun forwardOrCount(rec: StoredPacket, now: Long): Boolean {
        val cur = store.get(rec.id) ?: return false
        val hadLinks = links.isNotEmpty()
        val ok = hadLinks && forward(cur, exclude = null)
        if (ok) return true
        val retries = cur.retryCount + 1
        val backoff = minOf(2000L shl minOf(retries, 5), 60_000L)
        if (!cur.own) { // relayed packets wait (bounded by expiry + queue bound), they never exhaust a retry budget
            store.update(cur.copy(nextAttemptMs = now + backoff)); return false
        }
        if (retries >= cfg.maxRetries) {
            store.update(cur.copy(retryCount = retries, nextAttemptMs = Long.MAX_VALUE, relay = false,
                status = if (cur.own && cur.status != Status.DELIVERED) Status.FAILED else cur.status))
            listener.onStatus(cur.id)
        } else store.update(cur.copy(retryCount = retries, nextAttemptMs = now + backoff))
        return false
    }

    // ---------------------------------------------------------------- origination (public API)

    private fun originate(type: Int, dest: String?, group: String?, payload: ByteArray, ttl: Int, maxHop: Int,
                          initialStatus: Int, inner: Inner.Parsed? = null): SendResult {
        val now = clock()
        val p = try {
            val unsigned = Packet(Limits.VERSION, type, CryptoUtil.randomId(), identity.deviceId, dest, group, ttl, now, 0,
                maxHop.coerceIn(0, Limits.MAX_HOPS), identity.publicKeyBytes, payload, ByteArray(0))
            sign(unsigned)
        } catch (e: Exception) { return SendResult.Err("Could not build packet: ${e.message}") }
        val raw = try { PacketCodec.encode(p) } catch (e: PacketException) { return SendResult.Err(e.message ?: "Message too large") }
        val l = inner?.loc
        val rec = StoredPacket(p.id, type, p.senderId, dest, group, now, now, now + ttl * 1000L, 0, p.maxHop,
            PacketType.priority(type), own = true, relay = true, raw = raw, text = inner?.text,
            lat = l?.lat, lon = l?.lon, accM = l?.accuracyM, fixTimeMs = l?.fixTimeMs, status = initialStatus)
        if (!store.insertIfNew(rec)) return SendResult.Err("Duplicate id")
        forward(rec, null)
        return SendResult.Ok(p.id)
    }

    private fun body(text: String?, loc: LocationData?): Pair<ByteArray, Inner.Parsed>? = when {
        loc != null -> {
            if (!Inner.validLocation(loc.lat, loc.lon, loc.accuracyM, loc.fixTimeMs)) null
            else Inner.location(loc) to Inner.Parsed(loc.note.takeIf { it.isNotEmpty() }, loc)
        }
        !text.isNullOrBlank() -> {
            val t = text.take(Limits.MAX_TEXT_CHARS); Inner.text(t) to Inner.Parsed(t, null)
        }
        else -> null
    }

    fun sendDirect(destId: String, text: String? = null, loc: LocationData? = null): SendResult = synchronized(lock) {
        if (!PacketCodec.isId(destId)) return SendResult.Err("Invalid recipient")
        val peer = store.peer(destId) ?: return SendResult.Err("Recipient's key not known yet. Stay connected until their device is discovered.")
        val (b, inner) = body(text, loc) ?: return SendResult.Err("Nothing to send")
        val id = CryptoUtil.randomId()
        // id must be known for AAD, so build packet manually
        val now = clock()
        val dummy = Packet(Limits.VERSION, PacketType.DIRECT, id, identity.deviceId, destId, null, cfg.defaultTtlSec, now, 0, cfg.defaultMaxHop,
            identity.publicKeyBytes, ByteArray(0), ByteArray(0))
        val enc = CryptoUtil.encryptFor(peer.pubKey, aad(dummy), b)
        originateWithId(dummy.copy(payload = enc), inner)
    }

    fun sendGroup(groupId: String, text: String? = null, loc: LocationData? = null): SendResult = synchronized(lock) {
        val key = groups.key(groupId) ?: return SendResult.Err("Not a member of this group")
        val (b, inner) = body(text, loc) ?: return SendResult.Err("Nothing to send")
        val dummy = Packet(Limits.VERSION, PacketType.GROUP, CryptoUtil.randomId(), identity.deviceId, null, groupId, cfg.defaultTtlSec,
            clock(), 0, cfg.defaultMaxHop, identity.publicKeyBytes, ByteArray(0), ByteArray(0))
        originateWithId(dummy.copy(payload = CryptoUtil.encryptGroup(key, aad(dummy), b)), inner)
    }

    private fun originateWithId(unsigned: Packet, inner: Inner.Parsed): SendResult {
        val p = sign(unsigned)
        val raw = try { PacketCodec.encode(p) } catch (e: PacketException) { return SendResult.Err(e.message ?: "Message too large") }
        val l = inner.loc
        val now = clock()
        val rec = StoredPacket(p.id, p.type, p.senderId, p.destId, p.groupId, p.createdAtMs, now, now + p.ttlSec * 1000L, 0, p.maxHop,
            PacketType.priority(p.type), true, true, raw, inner.text, l?.lat, l?.lon, l?.accuracyM, l?.fixTimeMs, Status.PENDING)
        if (!store.insertIfNew(rec)) return SendResult.Err("Duplicate id")
        forward(rec, null)
        return SendResult.Ok(p.id)
    }

    fun sendBroadcast(text: String? = null, loc: LocationData? = null): SendResult = synchronized(lock) {
        val (b, inner) = body(text, loc) ?: return SendResult.Err("Nothing to send")
        originate(PacketType.BROADCAST, null, null, b, cfg.defaultTtlSec, cfg.defaultMaxHop, Status.PENDING, inner)
    }

    /** SOS is intentionally plaintext (signed) so that any device can relay and display it. */
    fun sendSos(loc: LocationData?, note: String): SendResult = synchronized(lock) {
        val n = note.take(Limits.MAX_TEXT_CHARS)
        val (b, inner) = (if (loc != null) body(null, loc.copy(note = n)) else body(n.ifBlank { "SOS" }, null))
            ?: return SendResult.Err("Invalid location")
        originate(PacketType.SOS, null, null, b, cfg.sosTtlSec, Limits.MAX_HOPS, Status.PENDING, inner)
    }

    fun resolveSos(sosId: String): Boolean = synchronized(lock) {
        val r = store.get(sosId) ?: return false
        if (!r.own || r.type != PacketType.SOS) return false
        store.update(r.copy(status = Status.RESOLVED, relay = false)); listener.onStatus(sosId)
        originate(PacketType.SOS_RESOLVE, null, null, PacketCodec.unhex(sosId), cfg.sosTtlSec, Limits.MAX_HOPS, Status.SENT)
        true
    }

    /** User-initiated retry of an own message that FAILED/EXPIRED-pending: resets budget and resend list. */
    fun retry(id: String): Boolean = synchronized(lock) {
        val r = store.get(id) ?: return false
        if (!r.own || r.raw == null || r.status == Status.DELIVERED) return false
        store.update(r.copy(status = Status.PENDING, retryCount = 0, nextAttemptMs = 0, sentTo = "", relay = true))
        flushPending(true); true
    }

    fun cancel(id: String): Boolean = synchronized(lock) {
        val r = store.get(id) ?: return false
        if (!r.own || r.status == Status.DELIVERED) return false
        store.update(r.copy(status = Status.CANCELLED, relay = false)); listener.onStatus(id); true
    }
}
