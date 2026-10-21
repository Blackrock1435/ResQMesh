package com.resqmesh.app.mesh

import android.content.Context
import com.resqmesh.app.data.DiagEntity
import com.resqmesh.app.data.GroupEntity
import com.resqmesh.app.data.MeshDatabase
import com.resqmesh.app.data.RoomMeshStore
import com.resqmesh.app.security.IdentityManager
import com.resqmesh.app.service.Notifier
import com.resqmesh.app.transport.Endpoint
import com.resqmesh.app.transport.EpState
import com.resqmesh.app.transport.NearbyConnectionsTransport
import com.resqmesh.app.transport.TransportEvents
import com.resqmesh.core.CryptoUtil
import com.resqmesh.core.EngineListener
import com.resqmesh.core.GroupKeys
import com.resqmesh.core.LocationData
import com.resqmesh.core.RelayEngine
import com.resqmesh.core.SendResult
import com.resqmesh.core.StoredPacket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class StatsSnap(
    val received: Long = 0, val duplicates: Long = 0, val malformed: Long = 0, val invalid: Long = 0, val expired: Long = 0,
    val relayed: Long = 0, val rateLimited: Long = 0, val lastTransferMs: Long = 0, val queueSize: Int = 0, val lastSyncMs: Long = 0,
)

/**
 * Glue between transport, relay engine and storage. ALL engine/store/transport.send calls run on ONE
 * background thread ("mesh-engine"); Nearby callbacks (main thread) only post work to it.
 */
class MeshManager(
    private val ctx: Context,
    private val db: MeshDatabase,
    private val idm: IdentityManager,
) : TransportEvents, EngineListener, GroupKeys {
    val identity = idm.identity()
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "mesh-engine") }
    private val groupKeys = ConcurrentHashMap<String, ByteArray>()
    private fun name() = idm.displayName ?: "Anonymous"

    val transport = NearbyConnectionsTransport(ctx, this, { identity.deviceId }, { name() }) { idm.autoJoin }
    val engine = RelayEngine(identity, { name() }, RoomMeshStore(db), transport, this, this)
    private val roomStore = RoomMeshStore(db)

    val networkOn = MutableStateFlow(false)
    val transportError = MutableStateFlow<String?>(null)
    val endpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    val stats = MutableStateFlow(StatsSnap())
    /** One-shot user-facing messages (errors, confirmations). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private var lastPurge = 0L
    private var lastSync = 0L

    init {
        exec.execute { db.groups().allNow().forEach { runCatching { groupKeys[it.groupId] = idm.unwrap(it.wrappedKey) } } }
        exec.scheduleWithFixedDelay({
            try { periodic() } catch (e: Throwable) { log("tick error: ${e.javaClass.simpleName}") }
        }, 5, 5, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- GroupKeys / EngineListener

    override fun key(groupId: String): ByteArray? = groupKeys[groupId]
    override fun onSos(rec: StoredPacket) { if (!rec.own) Notifier.sos(ctx, "An SOS alert was received from a nearby device.") }
    override fun log(msg: String) { // sanitized by construction: engine/transport log only event kinds, never content or coordinates
        runCatching { db.diag().insert(DiagEntity(atMs = System.currentTimeMillis(), msg = msg.take(200))) }
    }

    // ---------------------------------------------------------------- TransportEvents (called on main thread)

    override fun onEndpoints(list: List<Endpoint>) { endpoints.value = list }
    override fun onTransportError(msg: String?) { transportError.value = msg }
    override fun onLinkUp(linkId: String) { exec.execute { engine.onLinkUp(linkId); lastSync = System.currentTimeMillis(); log("link up") } }
    override fun onLinkDown(linkId: String) { exec.execute { engine.onLinkDown(linkId); log("link down") } }
    override fun onBytes(linkId: String, bytes: ByteArray) { exec.execute { engine.onBytes(linkId, bytes) } }

    // ---------------------------------------------------------------- lifecycle

    /** Caller must already hold the runtime permissions and have shown the consent dialog. */
    fun start() = exec.execute {
        val err = transport.availabilityError()
        if (err != null) { transportError.value = err; networkOn.value = false; return@execute }
        transportError.value = null
        transport.start(); networkOn.value = true; log("network started")
    }

    fun stop() = exec.execute { transport.stop(); networkOn.value = false; log("network stopped") }

    private fun periodic() {
        val now = System.currentTimeMillis()
        if (networkOn.value) transport.maintenance(now)
        engine.tick()
        if (now - lastPurge > 10 * 60_000L) {
            lastPurge = now
            roomStore.purgeHistory(now - idm.retentionDays * 24L * 3600_000L)
            db.diag().prune(now - 3L * 24 * 3600_000L)
        }
        val s = engine.stats
        stats.value = StatsSnap(s.received, s.duplicates, s.malformed, s.invalid, s.expired, s.relayed, s.rateLimited, s.lastTransferMs,
            db.packets().candidates(now, 1, 500).size, lastSync)
    }

    // ---------------------------------------------------------------- user actions (all hop to the engine thread)

    private fun report(r: SendResult) { if (r is SendResult.Err) toasts.tryEmit(r.reason) }

    /** key: "all" | "g:<groupId>" | "p:<deviceId>" */
    fun send(key: String, text: String?, loc: LocationData?) = exec.execute {
        report(when {
            key == "all" -> engine.sendBroadcast(text, loc)
            key.startsWith("g:") -> engine.sendGroup(key.drop(2), text, loc)
            key.startsWith("p:") -> engine.sendDirect(key.drop(2), text, loc)
            else -> SendResult.Err("Unknown conversation")
        })
    }

    fun sendSos(loc: LocationData?, note: String) = exec.execute {
        val r = engine.sendSos(loc, note)
        report(r)
        if (r is SendResult.Ok) toasts.tryEmit(
            if (engine.linkCount() == 0) "SOS saved. No nearby device is connected yet; it will be sent as soon as one connects."
            else "SOS passed to nearby devices. Delivery is not confirmed until acknowledgments appear."
        )
    }

    fun resolveSos(id: String) = exec.execute { engine.resolveSos(id) }
    fun retry(id: String) = exec.execute { engine.retry(id) }
    fun cancel(id: String) = exec.execute { engine.cancel(id) }
    fun connect(id: String) = exec.execute { transport.connect(id) }
    fun accept(id: String) = exec.execute { transport.accept(id) }
    fun reject(id: String) = exec.execute { transport.reject(id) }
    fun disconnect(id: String) = exec.execute { transport.disconnect(id) }

    /** Same group name + same passphrase on every member's phone => same group id and key. */
    fun joinGroup(displayName: String, passphrase: String) = exec.execute {
        val gid = displayName.lowercase().filter { it.isLetterOrDigit() }.take(24)
        if (gid.length < 3 || passphrase.length < 6) { toasts.tryEmit("Group name needs 3+ letters/digits and the passphrase 6+ characters."); return@execute }
        val key = CryptoUtil.groupKey(passphrase, gid)
        groupKeys[gid] = key
        db.groups().upsert(GroupEntity(gid, displayName.trim().take(32), idm.wrap(key), System.currentTimeMillis()))
    }

    fun connectedCount() = endpoints.value.count { it.state == EpState.CONNECTED }
}
