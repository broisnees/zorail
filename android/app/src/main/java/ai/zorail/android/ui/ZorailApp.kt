package ai.zorail.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("inboxes", "Mail", Icons.Default.Inbox),
    Tab("domain", "Domain", Icons.Default.Cloud),
    Tab("settings", "Settings", Icons.Default.Tune),
)

/** Route prefixes that should keep the Mail tab highlighted. */
private val MAIL_ROUTE_PREFIXES = listOf("inboxes", "inbox/", "message/")

/**
 * The app shell: a bottom bar over a nav graph.
 *
 * Bottom navigation rather than the web app's side rail, because a rail on a
 * 360dp-wide screen spends a sixth of the width on chrome and puts every target
 * out of thumb reach. The message list and reader are separate destinations, not
 * two panes, so Android's back gesture does the obvious thing.
 */
@Composable
fun ZorailApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val vm: ZorailViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // A freshly minted address is only useful if you can paste it somewhere, so
    // it goes to the clipboard and says so.
    val minted by vm.minted.collectAsStateWithLifecycle()
    LaunchedEffect(minted) {
        val addr = minted ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(addr))
        snackbar.showSnackbar("$addr — copied")
        vm.clearMinted()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEach { tab ->
                    val selected = if (tab.route == "inboxes") {
                        MAIL_ROUTE_PREFIXES.any { route?.startsWith(it) == true }
                    } else {
                        backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            NavHost(nav, startDestination = "inboxes") {
                composable("inboxes") {
                    InboxesScreen(
                        vm,
                        onOpen = { address -> nav.navigate("inbox/${Uri.encode(address)}") },
                        onNewAddress = { vm.generateAddress() },
                    )
                }
                // The address and id live in the route, not just in the view
                // model: Android restores the nav back stack after the process is
                // killed, but not the model, so a detail screen has to be able to
                // say what it is showing and re-fetch it.
                composable("inbox/{address}") { entry ->
                    val address = Uri.decode(entry.arguments?.getString("address").orEmpty())
                    LaunchedEffect(address) { vm.openInbox(address) }
                    InboxScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onOpenMessage = { id -> nav.navigate("message/$id") },
                    )
                }
                composable("message/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    LaunchedEffect(id) { vm.openMessage(id) }
                    ReaderScreen(vm, onBack = { nav.popBackStack() })
                }
                composable("domain") { CloudflareScreen(vm) }
                composable("settings") { SettingsScreen(vm) }
            }
        }
    }
}
