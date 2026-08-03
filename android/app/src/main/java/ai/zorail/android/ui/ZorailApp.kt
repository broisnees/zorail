package ai.zorail.android.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

    // The reader and the message list are pushed on top of the Mail tab; the bar
    // stays visible so switching tabs mid-read still works.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEach { tab ->
                    val selected = backStack?.destination?.hierarchy
                        ?.any { it.route == tab.route } == true ||
                        // Nested mail destinations keep the Mail tab lit.
                        (tab.route == "inboxes" && (route == "inbox" || route == "message"))
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
            val vm: ZorailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

            NavHost(nav, startDestination = "inboxes") {
                composable("inboxes") {
                    InboxesScreen(vm) { address ->
                        vm.openInbox(address)
                        nav.navigate("inbox")
                    }
                }
                composable("inbox") {
                    InboxScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onOpenMessage = { id ->
                            vm.openMessage(id)
                            nav.navigate("message")
                        },
                    )
                }
                composable("message") {
                    ReaderScreen(vm, onBack = { nav.popBackStack() })
                }
                composable("domain") { CloudflareScreen(vm) }
                composable("settings") { SettingsScreen(vm) }
            }
        }
    }
}
