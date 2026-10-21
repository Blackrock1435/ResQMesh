package com.resqmesh.core

import java.util.concurrent.ConcurrentHashMap

/** In-memory MeshStore used ONLY by tests. Thread-safe; insertIfNew is atomic. */
class MemoryStore : MeshStore {
    val packets = LinkedHashMap<String, StoredPacket>()
    val receiptList = ArrayList<ReceiptRecord>()
    val peers = HashMap<String, PeerRecord>()

    @Synchronized override fun insertIfNew(p: StoredPacket): Boolean { if (packets.containsKey(p.id)) return false; packets[p.id] = p; return true }
    @Synchronized override fun get(id: String) = packets[id]
    @Synchronized override fun update(p: StoredPacket) { packets[p.id] = p }
    @Synchronized override fun inventory(now: Long, limit: Int) =
        packets.values.filter { it.raw != null && it.relay && it.expiresAtMs > now }.sortedWith(compareBy<StoredPacket> { it.priority }.thenByDescending { it.receivedAtMs }).take(limit).map { it.id }
    @Synchronized override fun forwardCandidates(now: Long, ignoreBackoff: Boolean, limit: Int) =
        packets.values.filter { it.relay && it.raw != null && it.expiresAtMs > now && (ignoreBackoff || it.nextAttemptMs <= now) }
            .sortedWith(compareBy<StoredPacket> { it.priority }.thenBy { it.receivedAtMs }).take(limit)
    @Synchronized override fun expire(now: Long): List<String> {
        val out = ArrayList<String>()
        for ((id, p) in packets.entries.toList()) if (p.raw != null && p.expiresAtMs <= now) {
            val terminal = p.status == Status.DELIVERED || p.status == Status.RESOLVED || p.status == Status.CANCELLED
            packets[id] = p.copy(raw = null, relay = false, status = if (p.own && !terminal) Status.EXPIRED else p.status); out += id
        }
        return out
    }
    @Synchronized override fun purgeOlderThan(cutoffMs: Long): Int {
        val del = packets.values.filter { it.raw == null && it.text == null && it.lat == null && it.expiresAtMs < cutoffMs }.map { it.id }
        del.forEach { packets.remove(it) }; return del.size
    }
    @Synchronized override fun count() = packets.size
    @Synchronized override fun enforceBound(max: Int): Int {
        var n = 0
        while (packets.size > max) {
            val victim = packets.values.filter { !(it.own && it.type == PacketType.SOS && it.status != Status.RESOLVED) }
                .sortedWith(compareByDescending<StoredPacket> { it.priority }.thenBy { it.receivedAtMs }).firstOrNull() ?: break
            packets.remove(victim.id); n++
        }
        return n
    }
    @Synchronized override fun addReceipt(r: ReceiptRecord): Boolean {
        if (receiptList.any { it.messageId == r.messageId && it.peerId == r.peerId && it.kind == r.kind }) return false
        receiptList += r; return true
    }
    @Synchronized override fun receipts(messageId: String) = receiptList.filter { it.messageId == messageId }
    @Synchronized override fun upsertPeer(p: PeerRecord) { peers[p.deviceId] = p }
    @Synchronized override fun peer(deviceId: String) = peers[deviceId]
}

class Keys(private val map: MutableMap<String, ByteArray> = ConcurrentHashMap()) : GroupKeys {
    override fun key(groupId: String) = map[groupId]
    fun put(g: String, k: ByteArray) { map[g] = k }
}

/** Simulated radio network for tests: explicit links, queued delivery, controllable loss. Never used in production. */
class SimNet(var now: Long = 1_700_000_000_000L) {
    inner class Node(val name: String, val store: MemoryStore = MemoryStore(), val keys: Keys = Keys(), val identity: Identity = Identity.generate(),
                     cfg: EngineConfig = EngineConfig()) : Transport {
        val received = ArrayList<StoredPacket>()
        var dropIncomingType: Int? = null
        var failSends = false
        var engine = build(cfg)
        fun build(cfg: EngineConfig) = RelayEngine(identity, { name }, store, this, keys, object : EngineListener {
            override fun onMessage(rec: StoredPacket) { received += rec }
        }, { now }, cfg)
        override fun send(linkId: String, data: ByteArray): Boolean {
            if (failSends || !connected(name, linkId)) return false
            sentLog += Triple(name, linkId, data); queue.add(Triple(name, linkId, data)); return true
        }
        fun restart(cfg: EngineConfig = EngineConfig()) { engine = build(cfg) }
    }

    val nodes = LinkedHashMap<String, Node>()
    private val edges = HashSet<Set<String>>()
    private val queue = ArrayDeque<Triple<String, String, ByteArray>>()
    val sentLog = ArrayList<Triple<String, String, ByteArray>>()

    fun node(name: String, cfg: EngineConfig = EngineConfig()) = Node(name, cfg = cfg).also { nodes[name] = it }
    fun connected(a: String, b: String) = edges.contains(setOf(a, b))
    fun connect(a: String, b: String) { edges += setOf(a, b); nodes[a]!!.engine.onLinkUp(b); nodes[b]!!.engine.onLinkUp(a) }
    fun disconnect(a: String, b: String) {
        edges -= setOf(a, b); nodes[a]!!.engine.onLinkDown(b); nodes[b]!!.engine.onLinkDown(a)
        queue.removeAll { (f, t, _) -> setOf(f, t) == setOf(a, b) } // packets in flight are lost
    }
    fun pump(maxSteps: Int = 100_000) {
        var n = 0
        while (queue.isNotEmpty() && n++ < maxSteps) {
            val (from, to, data) = queue.removeFirst()
            val dst = nodes[to] ?: continue
            if (!connected(from, to)) continue
            val dt = dst.dropIncomingType
            if (dt != null) { val t = data.getOrNull(2)?.toInt(); if (t == dt) continue }
            dst.engine.onBytes(from, data)
        }
    }
    fun advance(ms: Long) { now += ms }
    fun tickAll() { nodes.values.forEach { it.engine.tick() }; pump() }
    fun chain(vararg names: String) { names.toList().zipWithNext().forEach { (a, b) -> connect(a, b) }; pump() }
}
