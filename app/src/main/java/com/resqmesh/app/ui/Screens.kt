package com.resqmesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.location.LocResult
import com.resqmesh.app.service.MeshForegroundService
import com.resqmesh.app.transport.EpState
import com.resqmesh.app.util.Perms
import com.resqmesh.app.util.ageText
import com.resqmesh.app.util.fmtCoord
import com.resqmesh.app.util.fmtTime
import com.resqmesh.app.util.shortId
import com.resqmesh.core.PacketCodec
import com.resqmesh.core.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Launches the runtime-permission dialog if needed, then calls [onGranted]; explains plainly on denial. */
@Composable
fun rememberPermissionAction(perms: Array<String>, onDenied: (String) -> Unit, onGranted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val miss = Perms.missingRequired(ctx, perms)
        if (miss.isEmpty()) onGranted() else onDenied("Permission needed: ${miss.joinToString { Perms.short(it) }}. Open Android Settings > Apps > ResQMesh > Permissions to allow it.")
    }
    return { if (Perms.missingRequired(ctx, perms).isEmpty()) onGranted() else launcher.launch(perms) }
}

@Composable
fun StatusRow(label: String, value: String, ok: Boolean? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, color = when (ok) { true -> Color(0xFF1B7F3B); false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurface })
    }
}

@Composable
fun BigAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Button(onClick, modifier.heightIn(min = 64.dp), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp)) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("coordinates", text))
}

// ------------------------------------------------------------------------------------------------ HOME

@Composable
fun HomeScreen(vm: MeshViewModel, onNav: (String) -> Unit) {
    val ctx = LocalContext.current
    val on by vm.mesh.networkOn.collectAsState()
    val err by vm.mesh.transportError.collectAsState()
    val eps by vm.mesh.endpoints.collectAsState()
    val stats by vm.mesh.stats.collectAsState()
    val pending by vm.pending.collectAsState()
    val sos by vm.sos.collectAsState()
    val loc by vm.location.last.collectAsState()
    var showConsent by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf<String?>(null) }
    val connected = eps.count { it.state == EpState.CONNECTED }
    val now = System.currentTimeMillis()
    val activeSos = sos.count { it.status in listOf(Status.PENDING, Status.SENT, Status.RECEIVED) && it.expiresAtMs > now }

    val startNet = rememberPermissionAction(Perms.transport(), { denied = it }) { vm.mesh.start(); MeshForegroundService.start(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ResQMesh", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (err != null) WarnCard(err!!)
        denied?.let { WarnCard(it) }
        if (!on) WarnCard("Network is OFF. Nobody nearby can reach you and you cannot reach them. Tap Start Network.")
        else if (connected == 0) WarnCard("Network is ON but no phone is connected yet. Messages will be saved and sent when a phone connects.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                StatusRow("Network", if (on) "ON (scanning)" else "OFF", on)
                StatusRow("Connected phones", connected.toString(), connected > 0)
                StatusRow("Internet", "not required", null)
                StatusRow("Device ID", shortId(vm.me))
                val l = loc
                StatusRow("Location", when (l) { is LocResult.Fix -> (if (l.stale) "STALE · " else "") + "±${l.loc.accuracyM.toInt()} m · ${ageText(l.loc.fixTimeMs)}"; is LocResult.Error -> "unavailable"; null -> "not checked" }, (l as? LocResult.Fix)?.let { !it.stale })
                StatusRow("Messages waiting", pending.toString(), if (pending == 0) null else false)
                StatusRow("Active SOS alerts", activeSos.toString(), if (activeSos == 0) null else false)
                StatusRow("Last transfer", fmtTime(stats.lastTransferMs))
            }
        }
        BigAction(if (on) "Stop Network" else "Start Network", {
            if (on) { vm.mesh.stop(); MeshForegroundService.stop(ctx) } else if (!vm.consent.value) showConsent = true else startNet()
        }, Modifier.fillMaxWidth(), color = if (on) Color(0xFF5F6368) else Color(0xFF1B7F3B))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction("Open Chats", { onNav("chats") }, Modifier.weight(1f))
            BigAction("Find Nearby Devices", { onNav("nearby") }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction("Share My Location", { onNav("location") }, Modifier.weight(1f))
            BigAction("SOS Emergency", { onNav("sos") }, Modifier.weight(1f), color = Color(0xFFB3261E))
        }
    }
    if (showConsent) AlertDialog(
        onDismissRequest = { showConsent = false },
        title = { Text("Make this phone discoverable?") },
        text = { Text("Starting the network makes your phone visible to nearby ResQMesh phones and scans for them using Bluetooth/Wi-Fi. It uses battery. Messages you send may be relayed through other people's phones (end-to-end encrypted for private chats; SOS alerts and broadcasts are readable by everyone who relays them).") },
        confirmButton = { TextButton({ vm.giveConsent(); showConsent = false; startNet() }) { Text("Start network") } },
        dismissButton = { TextButton({ showConsent = false }) { Text("Cancel") } },
    )
}

@Composable
fun WarnCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

// ------------------------------------------------------------------------------------------------ NEARBY

@Composable
fun NearbyScreen(vm: MeshViewModel) {
    val ctx = LocalContext.current
    val on by vm.mesh.networkOn.collectAsState()
    val eps by vm.mesh.endpoints.collectAsState()
    val err by vm.mesh.transportError.collectAsState()
    val auto by vm.autoJoin.collectAsState()
    var denied by remember { mutableStateOf<String?>(null) }
    var showConsent by remember { mutableStateOf(false) }
    val start = rememberPermissionAction(Perms.transport(), { denied = it }) { vm.mesh.start(); MeshForegroundService.start(ctx) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Nearby devices", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            err?.let { WarnCard(it) }; denied?.let { WarnCard(it) }
            Spacer8()
            BigAction(if (on) "Stop Network" else "Start Network", {
                if (on) { vm.mesh.stop(); MeshForegroundService.stop(ctx) } else if (!vm.consent.value) showConsent = true else start()
            }, Modifier.fillMaxWidth(), if (on) Color(0xFF5F6368) else Color(0xFF1B7F3B))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-join mesh", fontWeight = FontWeight.SemiBold)
                    Text("Connect to every nearby ResQMesh phone automatically (emergency mode). Off = you approve each phone after comparing the code.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(auto, { vm.setAutoJoin(it) })
            }
        }
        if (on && eps.isEmpty()) item { Text("No nearby devices found. Keep this screen open while the app searches. Make sure the other phone has also started its network.") }
        if (!on) item { Text("Network is off. Start it to discover nearby phones.") }
        items(eps, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(e.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    e.deviceId?.let { Text("ID ${shortId(it)}", style = MaterialTheme.typography.bodySmall) }
                    val (label, ok) = when (e.state) {
                        EpState.DISCOVERED -> "Found (not connected)" to null
                        EpState.CONNECTING -> "Connecting…" to null
                        EpState.AWAITING_APPROVAL -> "Waiting for your approval" to null
                        EpState.CONNECTED -> "Connected" to true
                        EpState.REJECTED -> "Rejected" to false
                        EpState.FAILED -> "Connection failed" to false
                        EpState.DISCONNECTED -> if (e.discovered) "Disconnected (in range)" to false else "Disconnected (out of range)" to false
                    }
                    StatusRow("State", label, ok)
                    if (e.state == EpState.AWAITING_APPROVAL) {
                        Text("Check that this code matches on the other phone: ${e.authDigits ?: "—"}", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ vm.mesh.accept(e.id) }) { Text("Accept") }
                            OutlinedButton({ vm.mesh.reject(e.id) }) { Text("Reject") }
                        }
                    }
                    if (e.state in listOf(EpState.DISCOVERED, EpState.FAILED, EpState.REJECTED, EpState.DISCONNECTED) && e.discovered)
                        Button({ vm.mesh.connect(e.id) }) { Text("Connect") }
                    if (e.state == EpState.CONNECTED) OutlinedButton({ vm.mesh.disconnect(e.id) }) { Text("Disconnect") }
                }
            }
        }
    }
    if (showConsent) AlertDialog(onDismissRequest = { showConsent = false }, title = { Text("Make this phone discoverable?") },
        text = { Text("Starting the network makes your phone visible to nearby ResQMesh phones and scans for them. This uses battery.") },
        confirmButton = { TextButton({ vm.giveConsent(); showConsent = false; start() }) { Text("Start network") } },
        dismissButton = { TextButton({ showConsent = false }) { Text("Cancel") } })
}

@Composable private fun Spacer8() { Box(Modifier.height(8.dp)) }

// ------------------------------------------------------------------------------------------------ LOCATION

@Composable
fun LocationScreen(vm: MeshViewModel) {
    val ctx = LocalContext.current
    val res by vm.location.last.collectAsState()
    val busy by vm.location.busy.collectAsState()
    var denied by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    val get = rememberPermissionAction(Perms.location, { denied = it }) { vm.refreshLocation() }
    val share = rememberPermissionAction(Perms.location, { denied = it }) { consent = true }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My location", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("GPS works without internet. A first fix can take up to a minute and may fail indoors. Nothing is shared until you choose to.", style = MaterialTheme.typography.bodyMedium)
        denied?.let { WarnCard(it) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                when (val r = res) {
                    null -> Text("No location yet. Tap “Get my location”.")
                    is LocResult.Error -> Text(r.reason, color = MaterialTheme.colorScheme.error)
                    is LocResult.Fix -> {
                        Text(fmtCoord(r.loc.lat, r.loc.lon), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        StatusRow("Accuracy", "±${r.loc.accuracyM.toInt()} m", r.loc.accuracyM <= 50)
                        StatusRow("Fix time", fmtTime(r.loc.fixTimeMs))
                        StatusRow("Age", ageText(r.loc.fixTimeMs), !r.stale)
                        if (r.stale) Text("This is a last-known location and may be outdated.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton({ vm.cancelLocation() }) { Text("Cancel") } }
        else BigAction(if (res is LocResult.Error) "Retry" else "Get my location", { get() }, Modifier.fillMaxWidth())
        val fix = res as? LocResult.Fix
        if (fix != null) {
            OutlinedButton({ copyText(ctx, fmtCoord(fix.loc.lat, fix.loc.lon)) }, Modifier.fillMaxWidth()) { Text("Copy coordinates") }
            BigAction("Share to everyone nearby", { share() }, Modifier.fillMaxWidth())
            OutlinedButton({
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${fix.loc.lat},${fix.loc.lon}?q=${fix.loc.lat},${fix.loc.lon}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { vm.mesh.toasts.tryEmit("No map app installed.") }
            }, Modifier.fillMaxWidth()) { Text("Open in external map app (map tiles need internet)") }
        }
        Text("No offline map is bundled in this prototype: coordinates are always shown as numbers so they work everywhere.", style = MaterialTheme.typography.bodySmall)
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Share your location?" ) },
        text = { Text("Your exact coordinates, accuracy and fix time will be sent to every reachable phone and relayed through others. Anyone who relays it can read it.") },
        confirmButton = { TextButton({ consent = false; vm.shareLocation("all") }) { Text("Share") } },
        dismissButton = { TextButton({ consent = false }) { Text("Cancel") } })
}

// ------------------------------------------------------------------------------------------------ SOS

@Composable
fun HoldButton(label: String, holdMs: Long = 1500, onTriggered: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFB3261E))
            .semantics { contentDescription = "$label. Press and hold for a second and a half."; onClick(label = "Send SOS now") { onTriggered(); true } }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        val t0 = SystemClock.elapsedRealtime()
                        while (true) {
                            val p = (SystemClock.elapsedRealtime() - t0).toFloat() / holdMs
                            progress = p.coerceAtMost(1f)
                            if (p >= 1f) { onTriggered(); break }
                            delay(30)
                        }
                    }
                    tryAwaitRelease(); job.cancel(); progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(progress).height(120.dp).background(Color(0x66FFFFFF)))
        Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun SosScreen(vm: MeshViewModel) {
    val sos by vm.sos.collectAsState()
    val peers by vm.peers.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val eps by vm.mesh.endpoints.collectAsState()
    val loc by vm.location.last.collectAsState()
    var incl by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val names = remember(peers) { peers.associate { it.deviceId to it.name } }
    val askLoc = rememberPermissionAction(Perms.location, { denied = it }) { vm.refreshLocation(8_000) }
    val connected = eps.count { it.state == EpState.CONNECTED }

    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SOS Emergency", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("This sends an alert to nearby ResQMesh phones, which relay it onward. It does NOT contact emergency services (police, ambulance, fire). Call them if you can.", color = MaterialTheme.colorScheme.error)
            Spacer8()
            Text("The alert shares: your display name and device ID, your note, and (if enabled and available) your GPS coordinates, accuracy and fix time. It is readable by every phone that relays it.")
            denied?.let { WarnCard(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(incl, { incl = it; if (it && !vm.location.hasPermission()) askLoc() }); Text("  Include my location")
            }
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("Short note (optional)") }, modifier = Modifier.fillMaxWidth())
            if (connected == 0) WarnCard("No phone is connected right now. The alert will be saved and sent as soon as one connects.")
            Spacer8()
            HoldButton("HOLD TO SEND SOS") { vm.triggerSos(incl, note); note = "" }
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Alternative: tap, then confirm") }
        }
        item { Text("Alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (sos.isEmpty()) item { Text("No alerts sent or received.") }
        items(sos, key = { it.id }) { s ->
            val mine = s.own
            val acks = receipts.filter { it.messageId == s.id }.map { names[it.peerId] ?: shortId(it.peerId) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (s.status == Status.RESOLVED) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (mine) "Your alert" else "From ${names[s.senderId] ?: shortId(s.senderId)}", fontWeight = FontWeight.Bold)
                    Text("Created ${fmtTime(s.createdAtMs)}")
                    s.text?.let { Text(it) }
                    if (s.lat != null && s.lon != null) {
                        Text(fmtCoord(s.lat, s.lon), fontWeight = FontWeight.SemiBold)
                        Text("±${s.accM?.toInt() ?: "?"} m · fix ${ageText(s.fixTimeMs ?: 0, s.createdAtMs)} before sending" + if ((s.createdAtMs - (s.fixTimeMs ?: 0)) > 60_000) " · STALE" else "")
                        TextButton({ copyText(ctx, fmtCoord(s.lat, s.lon)) }) { Text("Copy coordinates") }
                    } else Text("No location included.")
                    if (mine) {
                        Text(Status.label(s.status), fontWeight = FontWeight.SemiBold)
                        Text(if (acks.isEmpty()) "No device has acknowledged yet." else "Acknowledged by: ${acks.joinToString()}")
                        if (s.status in listOf(Status.PENDING, Status.SENT, Status.FAILED)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.status == Status.FAILED) Button({ vm.mesh.retry(s.id) }) { Text("Retry") }
                            OutlinedButton({ vm.mesh.resolveSos(s.id) }) { Text("Cancel / I'm safe") }
                        }
                    } else Text(if (s.status == Status.RESOLVED) "Resolved by sender." else "Acknowledged automatically. Not verified by any authority.")
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Send SOS now?") },
        text = { Text("Nearby phones will be alerted and will relay it. Emergency services are NOT contacted.") },
        confirmButton = { TextButton({ confirm = false; vm.triggerSos(incl, note); note = "" }) { Text("SEND SOS") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
