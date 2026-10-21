package com.resqmesh.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqmesh.app.ResQApp
import com.resqmesh.app.data.PacketEntity
import com.resqmesh.app.location.LocResult
import com.resqmesh.core.LocationData
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as ResQApp
    val mesh = a.mesh
    val idm = a.idm
    val location = a.location
    private val db = a.db
    val me: String = mesh.identity.deviceId

    val name = MutableStateFlow(idm.displayName)
    val autoJoin = MutableStateFlow(idm.autoJoin)
    val consent = MutableStateFlow(idm.networkConsent)

    private fun <T> Flow<T>.hot(init: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val peers = db.peers().all().hot(emptyList())
    val groups = db.groups().all().hot(emptyList())
    val receipts = db.receipts().all().hot(emptyList())
    val pending = db.packets().pendingCount().hot(0)
    val sos = db.packets().sos().hot(emptyList())
    val diag = db.diag().recent(100).hot(emptyList())

    fun thread(key: String): Flow<List<PacketEntity>> = when {
        key == "all" -> db.packets().broadcast()
        key.startsWith("g:") -> db.packets().group(key.drop(2))
        else -> db.packets().direct(me, key.drop(2))
    }

    fun setName(n: String) { idm.displayName = n; name.value = idm.displayName }
    fun setAutoJoin(v: Boolean) { idm.autoJoin = v; autoJoin.value = v }
    fun giveConsent() { idm.networkConsent = true; consent.value = true }

    private var locJob: Job? = null
    fun refreshLocation(timeoutMs: Long = 30_000) {
        locJob?.cancel(); locJob = viewModelScope.launch { location.acquire(timeoutMs) }
    }
    fun cancelLocation() { locJob?.cancel(); location.busy.value = false }

    /** Sends the current/last-known fix to a conversation. Never sends a made-up coordinate. */
    fun shareLocation(key: String, note: String = "") {
        viewModelScope.launch {
            when (val r = location.acquire(15_000)) {
                is LocResult.Fix -> mesh.send(key, null, r.loc.copy(note = note + if (r.stale) " (last known, may be outdated)" else ""))
                is LocResult.Error -> mesh.toasts.tryEmit(r.reason)
            }
        }
    }

    /** SOS must never be blocked by location: wait at most 6 s for a fix, then send with whatever is available. */
    fun triggerSos(includeLocation: Boolean, note: String) {
        viewModelScope.launch {
            var loc: LocationData? = null
            if (includeLocation && location.hasPermission()) {
                val r = location.acquire(6_000)
                if (r is LocResult.Fix) loc = r.loc
            }
            mesh.sendSos(loc, note)
        }
    }
}
