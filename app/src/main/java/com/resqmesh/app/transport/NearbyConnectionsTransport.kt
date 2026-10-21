package com.resqmesh.app.transport

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Tasks
import com.resqmesh.core.Transport
import java.util.concurrent.TimeUnit

enum class EpState { DISCOVERED, CONNECTING, AWAITING_APPROVAL, CONNECTED, REJECTED, FAILED, DISCONNECTED }

data class Endpoint(
    val id: String, val name: String, val deviceId: String?, val state: EpState,
    val authDigits: String? = null, val incoming: Boolean = false,
    val sinceMs: Long = System.currentTimeMillis(), val attempts: Int = 0, val discovered: Boolean = true,
)

interface TransportEvents {
    fun onEndpoints(list: List<Endpoint>)
    fun onLinkUp(linkId: String)
    fun onLinkDown(linkId: String)
    fun onBytes(linkId: String, bytes: ByteArray)
    fun onTransportError(msg: String?)
    fun log(msg: String)
}

/**
 * Transport implementation on Google Nearby Connections (Strategy.P2P_CLUSTER = M-to-N topology).
 * Everything above this class only knows the [Transport] interface, so it can be swapped for raw
 * Bluetooth / Wi-Fi Direct later.
 *
 * Link id == Nearby endpointId (changes per session). Stable identity is established by the signed HELLO
 * exchanged by the relay engine, NOT by the advertised name.
 */
class NearbyConnectionsTransport(
    ctx: Context,
    private val events: TransportEvents,
    private val localDeviceId: () -> String,
    private val localName: () -> String,
    private val autoJoin: () -> Boolean,
) : Transport {
    private val appCtx = ctx.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appCtx)
    private val serviceId = "com.resqmesh.mesh.v1"
    private val strategy = Strategy.P2P_CLUSTER
    private val eps = LinkedHashMap<String, Endpoint>()
    @Volatile private var running = false

    /** null = OK, otherwise a plain-English explanation. */
    fun availabilityError(): String? =
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appCtx) == ConnectionResult.SUCCESS) null
        else "Google Play services is missing or needs an update on this phone. Nearby messaging cannot start. (Internet is only needed once to install or update it.)"

    private fun advertisedName() = "${localName().replace("|", "")}|${localDeviceId()}"
    private fun parseDeviceId(name: String): String? =
        name.substringAfterLast('|', "").takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    private fun parseName(name: String) = name.substringBeforeLast('|').take(32).ifBlank { "Unknown" }

    private fun publish() { events.onEndpoints(synchronized(eps) { eps.values.toList() }) }
    private fun set(id: String, f: (Endpoint) -> Endpoint) { synchronized(eps) { eps[id]?.let { eps[id] = f(it) } }; publish() }

    fun start() {
        running = true
        val adv = AdvertisingOptions.Builder().setStrategy(strategy).build()
        val disc = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(advertisedName(), serviceId, lifecycle, adv)
            .addOnFailureListener { fail("Could not make this phone discoverable", it) }
        client.startDiscovery(serviceId, discovery, disc)
            .addOnFailureListener { fail("Could not search for nearby phones", it) }
    }

    private fun fail(what: String, e: Exception) {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return
        events.log("$what (code=$code)")
        events.onTransportError("$what. Check that Bluetooth and Wi-Fi are on, Location is on (needed by Android for scanning on older versions), and all permissions are granted. (code $code)")
    }

    fun stop() {
        running = false
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        val connected = synchronized(eps) { eps.values.filter { it.state == EpState.CONNECTED }.map { it.id }.also { eps.clear() } }
        connected.forEach { events.onLinkDown(it) }
        publish()
    }

    // ---------------------------------------------------------------- user actions

    fun connect(id: String) {
        val e = synchronized(eps) { eps[id] } ?: return
        if (e.state == EpState.CONNECTED || e.state == EpState.CONNECTING) return
        set(id) { it.copy(state = EpState.CONNECTING, sinceMs = System.currentTimeMillis(), attempts = it.attempts + 1) }
        client.requestConnection(advertisedName(), id, lifecycle).addOnFailureListener { ex ->
            val code = (ex as? com.google.android.gms.common.api.ApiException)?.statusCode
            if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return@addOnFailureListener
            events.log("requestConnection failed code=$code")
            set(id) { it.copy(state = EpState.FAILED, sinceMs = System.currentTimeMillis()) }
        }
    }

    fun accept(id: String) { client.acceptConnection(id, payloads).addOnFailureListener { set(id) { e -> e.copy(state = EpState.FAILED) } } }
    fun reject(id: String) { client.rejectConnection(id); set(id) { it.copy(state = EpState.REJECTED, sinceMs = System.currentTimeMillis()) } }
    fun disconnect(id: String) { client.disconnectFromEndpoint(id); markDisconnected(id) }

    /** Called every few seconds by MeshManager: timeouts and bounded auto-retry (max 3 attempts, growing delay). */
    fun maintenance(now: Long) {
        if (!running) return
        val snapshot = synchronized(eps) { eps.values.toList() }
        for (e in snapshot) {
            when (e.state) {
                EpState.CONNECTING, EpState.AWAITING_APPROVAL -> if (now - e.sinceMs > 30_000) {
                    client.disconnectFromEndpoint(e.id); set(e.id) { it.copy(state = EpState.FAILED, sinceMs = now) }
                }
                EpState.FAILED, EpState.DISCONNECTED -> if (autoJoin() && e.discovered && e.attempts < 3 &&
                    now - e.sinceMs > 10_000L * (e.attempts + 1) && shouldInitiate(e)) connect(e.id)
                else -> {}
            }
        }
    }

    /** Only the side with the lexicographically smaller device id initiates, avoiding simultaneous-request collisions. */
    private fun shouldInitiate(e: Endpoint) = e.deviceId != null && localDeviceId() < e.deviceId

    private fun markDisconnected(id: String) {
        var wasConnected = false
        synchronized(eps) { eps[id]?.let { wasConnected = it.state == EpState.CONNECTED
            eps[id] = it.copy(state = EpState.DISCONNECTED, sinceMs = System.currentTimeMillis()) } }
        publish()
        if (wasConnected) events.onLinkDown(id)
    }

    // ---------------------------------------------------------------- Nearby callbacks

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val devId = parseDeviceId(info.endpointName) ?: return           // not a ResQMesh peer
            if (devId == localDeviceId()) return
            synchronized(eps) {                                              // duplicate callbacks are expected
                val old = eps[id]
                eps[id] = old?.copy(name = parseName(info.endpointName), discovered = true)
                    ?: Endpoint(id, parseName(info.endpointName), devId, EpState.DISCOVERED)
            }
            publish()
            val e = synchronized(eps) { eps[id] }!!
            if (autoJoin() && e.state == EpState.DISCOVERED && shouldInitiate(e)) connect(id)
        }

        override fun onEndpointLost(id: String) {
            synchronized(eps) {
                val e = eps[id] ?: return
                if (e.state == EpState.CONNECTED) eps[id] = e.copy(discovered = false) else eps.remove(id)
            }
            publish()
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            val devId = parseDeviceId(info.endpointName)
            if (devId == null) { client.rejectConnection(id); return }
            val iRequested = synchronized(eps) { eps[id]?.state == EpState.CONNECTING }
            synchronized(eps) {
                val old = eps[id]
                eps[id] = (old ?: Endpoint(id, parseName(info.endpointName), devId, EpState.DISCOVERED)).copy(
                    name = parseName(info.endpointName), deviceId = devId, authDigits = info.authenticationDigits,
                    incoming = info.isIncomingConnection,
                    state = if (iRequested || autoJoin()) EpState.CONNECTING else EpState.AWAITING_APPROVAL,
                    sinceMs = System.currentTimeMillis())
            }
            publish()
            if (iRequested || autoJoin()) accept(id)  // otherwise the user must tap Accept after comparing the code
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    set(id) { it.copy(state = EpState.CONNECTED, sinceMs = System.currentTimeMillis(), attempts = 0) }
                    events.onLinkUp(id)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    set(id) { it.copy(state = EpState.REJECTED, sinceMs = System.currentTimeMillis()) }
                else -> set(id) { it.copy(state = EpState.FAILED, sinceMs = System.currentTimeMillis()) }
            }
        }

        override fun onDisconnected(id: String) { markDisconnected(id) }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            payload.asBytes()?.let { events.onBytes(id, it) }               // FILE/STREAM payloads are ignored
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    /** Blocking (<=5 s): MUST be called from the mesh engine thread, never from the main thread. */
    override fun send(linkId: String, data: ByteArray): Boolean = try {
        Tasks.await(client.sendPayload(linkId, Payload.fromBytes(data)), 5, TimeUnit.SECONDS); true
    } catch (e: Exception) { false }
}
