package com.resqmesh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.resqmesh.app.ui.ChatListScreen
import com.resqmesh.app.ui.ChatScreen
import com.resqmesh.app.ui.DiagnosticsScreen
import com.resqmesh.app.ui.HomeScreen
import com.resqmesh.app.ui.LocationScreen
import com.resqmesh.app.ui.MeshViewModel
import com.resqmesh.app.ui.NearbyScreen
import com.resqmesh.app.ui.SettingsScreen
import com.resqmesh.app.ui.SosScreen
import com.resqmesh.app.util.Perms

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResQTheme { Root() } }
    }
}

@Composable
fun ResQTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF9ECAFF), error = Color(0xFFFFB4AB), background = Color(0xFF101418), surface = Color(0xFF101418))
    else lightColorScheme(primary = Color(0xFF0B57D0), error = Color(0xFFB3261E), background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Root(vm: MeshViewModel = viewModel()) {
    val name by vm.name.collectAsState()
    if (name.isNullOrBlank()) Onboarding(vm) else AppScaffold(vm)
}

@Composable
fun Onboarding(vm: MeshViewModel) {
    var n by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("ResQMesh", style = MaterialTheme.typography.headlineLarge)
        Text("Offline emergency messaging between nearby phones", style = MaterialTheme.typography.titleMedium)
        Text("• Works without internet, mobile data, a SIM card or any server.\n" +
            "• Messages hop from phone to phone, so people out of direct range can still be reached.\n" +
            "• You will be asked for Bluetooth/nearby-device and location permissions only when you use those features.")
        Text("Limits you must know:", style = MaterialTheme.typography.titleSmall)
        Text("• This is a prototype, NOT a certified life-safety system. It never contacts emergency services.\n" +
            "• Delivery is never guaranteed. The app tells you honestly when a message is only saved or only passed to a nearby phone.\n" +
            "• Range is limited (tens of metres per hop). GPS may not work indoors.")
        OutlinedTextField(n, { n = it.take(32) }, label = { Text("Your display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.setName(n) }, enabled = n.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        Text("Your device ID is generated randomly on this phone and stays local.", style = MaterialTheme.typography.bodySmall)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppScaffold(vm: MeshViewModel) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val tabs = listOf(Tab("home", "Home", Icons.Filled.Home), Tab("chats", "Chats", Icons.Filled.Chat), Tab("nearby", "Nearby", Icons.Filled.Sensors),
        Tab("location", "Location", Icons.Filled.LocationOn), Tab("sos", "SOS", Icons.Filled.Warning), Tab("settings", "Settings", Icons.Filled.Settings))
    LaunchedEffect(Unit) { vm.mesh.toasts.collect { snack.showSnackbar(it) } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore("/")
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = {
                        nav.navigate(t.route) { popUpTo("home"); launchSingleTop = true }
                    }, icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(vm, onNav = { nav.navigate(it) { launchSingleTop = true } }) }
            composable("chats") { ChatListScreen(vm, onOpen = { nav.navigate("chat/$it") }) }
            composable("chat/{key}", listOf(navArgument("key") { type = NavType.StringType })) {
                ChatScreen(vm, it.arguments?.getString("key") ?: "all", onBack = { nav.popBackStack() })
            }
            composable("nearby") { NearbyScreen(vm) }
            composable("location") { LocationScreen(vm) }
            composable("sos") { SosScreen(vm) }
            composable("settings") { SettingsScreen(vm, onDiag = { nav.navigate("diag") }) }
            composable("diag") { DiagnosticsScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }
}
