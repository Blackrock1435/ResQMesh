package com.resqmesh.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.data.PacketEntity
import com.resqmesh.app.transport.EpState
import com.resqmesh.app.util.Perms
import com.resqmesh.app.util.ageText
import com.resqmesh.app.util.fmtCoord
import com.resqmesh.app.util.fmtTime
import com.resqmesh.app.util.shortId
import com.resqmesh.core.PacketType
import com.resqmesh.core.Status

// ------------------------------------------------------------------------------------------------ CHAT LIST

@Composable
fun ChatListScreen(vm: MeshViewModel, onOpen: (String) -> Unit) {
    val peers by vm.peers.collectAsState()
    val groups by vm.groups.collectAsState()
    val eps by vm.mesh.endpoints.collectAsState()
    val linked = eps.filter { it.state == EpState.CONNECTED }.mapNotNull { it.deviceId }.toSet()
    var dlg by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { ChatRow("Everyone nearby (broadcast)", "Readable by every phone that relays it. Not private.") { onOpen("all") } }
        item { Text("Groups", style = MaterialTheme.typography.titleMedium) }
        items(groups, key = { it.groupId }) { g -> ChatRow(g.name, "Encrypted with the group passphrase") { onOpen("g:${g.groupId}") } }
        item { OutlinedButton({ dlg = true }, Modifier.fillMaxWidth()) { Text("Create or join a group") } }
        item { Text("People", style = MaterialTheme.typography.titleMedium) }
        val others = peers.filter { it.deviceId != vm.me }
        if (others.isEmpty()) item { Text("No people seen yet. Start the network and connect to nearby phones; their names appear here once their identity is received (it can arrive through other phones too).") }
        items(others, key = { it.deviceId }) { p ->
            ChatRow(p.name, (if (p.deviceId in linked) "Directly connected" else "Not directly connected · last heard ${ageText(p.lastSeenMs)}") + " · ${shortId(p.deviceId)}") { onOpen("p:${p.deviceId}") }
        }
    }
    if (dlg) {
        var gname by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { dlg = false }, title = { Text("Create or join a group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everyone who types the same group name and passphrase is in the same group. Share the passphrase in person.")
                    OutlinedTextField(gname, { gname = it.take(24) }, label = { Text("Group name (letters/digits)") }, singleLine = true)
                    OutlinedTextField(pass, { pass = it.take(64) }, label = { Text("Passphrase (6+ characters)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ vm.mesh.joinGroup(gname, pass); dlg = false }) { Text("Save") } },
            dismissButton = { TextButton({ dlg = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ChatRow(title: String, sub: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp).heightIn(min = 48.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------------------------------------ CHAT

@Composable
fun ChatScreen(vm: MeshViewModel, key: String, onBack: () -> Unit) {
    val msgs by remember(key) { vm.thread(key) }.collectAsState(emptyList())
    val peers by vm.peers.collectAsState()
    val groups by vm.groups.collectAsState()
    val receipts by vm.receipts.collectAsState()
    val names = remember(peers) { peers.associate { it.deviceId to it.name } }
    val title = when { key == "all" -> "Everyone nearby"; key.startsWith("g:") -> groups.firstOrNull { it.groupId == key.drop(2) }?.name ?: "Group"; else -> names[key.drop(2)] ?: "Unknown" }
    var text by remember { mutableStateOf("") }
    var denied by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    val share = rememberPermissionAction(Perms.location, { denied = it }) { consent = true }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (key == "all") WarnCard("Broadcast messages are NOT private: any phone that relays them can read them.")
        denied?.let { WarnCard(it) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty()) item { Text("No messages yet. Messages you send are saved here even when nobody is in range.", Modifier.padding(8.dp)) }
            items(msgs, key = { it.id }) { m ->
                val acks = receipts.filter { it.messageId == m.id }.map { names[it.peerId] ?: shortId(it.peerId) }
                Bubble(m, names, acks, onRetry = { vm.mesh.retry(m.id) })
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ share() }, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.MyLocation, contentDescription = "Send my location") }
            OutlinedTextField(text, { text = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4)
            IconButton({ if (text.isNotBlank()) { vm.mesh.send(key, text.trim(), null); text = "" } }, Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Send your location here?") },
        text = { Text(if (key == "all") "Everyone who relays it can read your exact coordinates." else "Your exact coordinates will be sent encrypted to this conversation.") },
        confirmButton = { TextButton({ consent = false; vm.shareLocation(key) }) { Text("Send") } },
        dismissButton = { TextButton({ consent = false }) { Text("Cancel") } })
}

@Composable
private fun Bubble(m: PacketEntity, names: Map<String, String>, acks: List<String>, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val bg = if (m.own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.own) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth(0.85f)) {
            Column(Modifier.padding(10.dp)) {
                Text(if (m.own) "You" else names[m.senderId] ?: shortId(m.senderId), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                m.text?.let { Text(it, fontSize = 16.sp) }
                if (m.lat != null && m.lon != null) {
                    Text("📍 " + fmtCoord(m.lat, m.lon), fontWeight = FontWeight.SemiBold)
                    Text("±${m.accM?.toInt() ?: "?"} m · fix ${fmtTime(m.fixTimeMs ?: 0)}" + if (m.createdAtMs - (m.fixTimeMs ?: 0) > 60_000) " · STALE" else "")
                    TextButton({ copyText(ctx, fmtCoord(m.lat, m.lon)) }) { Text("Copy coordinates") }
                }
                Text(fmtTime(m.createdAtMs), style = MaterialTheme.typography.bodySmall)
                if (m.own) {
                    val color = when (m.status) { Status.DELIVERED -> Color(0xFF1B7F3B); Status.FAILED, Status.EXPIRED -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
                    Text(Status.label(m.status), color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (m.type == PacketType.GROUP) Text(if (acks.isEmpty()) "No member has confirmed yet." else "Confirmed by: ${acks.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    if (m.status == Status.FAILED || m.status == Status.EXPIRED) TextButton(onRetry) { Text("Retry") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ SETTINGS

@Composable
fun SettingsScreen(vm: MeshViewModel, onDiag: () -> Unit) {
    val name by vm.name.collectAsState()
    var n by remember(name) { mutableStateOf(name ?: "") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(n, { n = it.take(32) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ vm.setName(n) }, enabled = n.isNotBlank() && n != name) { Text("Save name") }
        Text("Your name is sent to nearby phones with your signed identity announcement. Device ID: ${vm.me}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onDiag, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Diagnostics & troubleshooting") }
        Text("About & limitations", style = MaterialTheme.typography.titleMedium)
        Text("ResQMesh is a prototype to assist communication. It is not a certified life-safety system, never contacts emergency services, and cannot guarantee delivery, range or GPS accuracy.\n\n" +
            "Private chats are end-to-end encrypted. Group chats are encrypted with the shared passphrase. Broadcasts and SOS alerts are signed but readable by anyone who relays them.\n\n" +
            "Messages are stored unencrypted-at-rest in this app's private storage (Android app sandbox) and removed after the retention period.")
    }
}

// ------------------------------------------------------------------------------------------------ DIAGNOSTICS

@Composable
fun DiagnosticsScreen(vm: MeshViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val stats by vm.mesh.stats.collectAsState()
    val eps by vm.mesh.endpoints.collectAsState()
    val on by vm.mesh.networkOn.collectAsState()
    val err by vm.mesh.transportError.collectAsState()
    val diag by vm.diag.collectAsState()
    val loc by vm.location.last.collectAsState()
    val miss = Perms.missing(ctx, Perms.transport()).map { Perms.short(it) }
    val locMiss = Perms.missing(ctx, Perms.location).isNotEmpty()
    val report = buildString {
        appendLine("ResQMesh diagnostics (no message contents, no coordinates)")
        appendLine("Time: ${fmtTime(System.currentTimeMillis())}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Device: ${shortId(vm.me)}; network on=$on; transport error=${err ?: "none"}")
        appendLine("Missing permissions: ${miss.ifEmpty { listOf("none") }}; location permission=${if (locMiss) "missing" else "granted"}")
        appendLine("Peers: ${eps.joinToString { "${it.state}" }}")
        appendLine("Stats: $stats")
        diag.take(60).forEach { appendLine("${fmtTime(it.atMs)}  ${it.msg}") }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onBack) { Text("‹ Back") }; Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                StatusRow("Local device ID", shortId(vm.me))
                StatusRow("Transport", if (err == null) (if (on) "running" else "stopped") else "UNAVAILABLE", err == null && on)
                StatusRow("Missing permissions", if (miss.isEmpty()) "none" else miss.joinToString(), miss.isEmpty())
                StatusRow("Location", when (loc) { is com.resqmesh.app.location.LocResult.Fix -> "fix available"; is com.resqmesh.app.location.LocResult.Error -> "error"; null -> "not checked" })
                StatusRow("Connected peers", eps.count { it.state == EpState.CONNECTED }.toString())
                StatusRow("Last packet transfer", fmtTime(stats.lastTransferMs))
                StatusRow("Pending queue", stats.queueSize.toString())
                StatusRow("Packets relayed", stats.relayed.toString())
                StatusRow("Expired", stats.expired.toString())
                StatusRow("Received / duplicates", "${stats.received} / ${stats.duplicates}")
                StatusRow("Rejected (malformed/invalid/rate)", "${stats.malformed}/${stats.invalid}/${stats.rateLimited}")
            }
        }
        Text("Peers", style = MaterialTheme.typography.titleMedium)
        if (eps.isEmpty()) Text("none") else eps.forEach { Text("${it.name} (${it.deviceId?.let(::shortId) ?: "?"}): ${it.state}") }
        Button({
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Export diagnostics"))
        }, Modifier.fillMaxWidth()) { Text("Export diagnostic log") }
        Text("Recent events", style = MaterialTheme.typography.titleMedium)
        diag.take(40).forEach { Text("${fmtTime(it.atMs)}  ${it.msg}", style = MaterialTheme.typography.bodySmall) }
        Text("Common fixes: turn Bluetooth, Wi-Fi and Location ON (airplane mode with Bluetooth+Wi-Fi re-enabled is fine); grant all requested permissions; keep phones within ~10-30 m; update Google Play services; start the network on BOTH phones.", style = MaterialTheme.typography.bodySmall)
    }
}
