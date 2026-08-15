package com.nexusagent

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
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
import com.nexusagent.debug.PerceptionDebugScreen
import com.nexusagent.history.HistoryScreen
import com.nexusagent.onboarding.OnboardingScreen
import com.nexusagent.settings.SettingsScreen
import com.nexusagent.task.TaskScreen

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    // Task is first: it is what the app is *for*. Setup and Perception are scaffolding
    // that matters on day one and rarely afterwards.
    Task("task", "Task", Icons.Default.AutoAwesome),
    History("history", "History", Icons.Default.History),
    Perception("perception", "Perception", Icons.Default.Visibility),
    Setup("setup", "Setup", Icons.Default.Tune),
    Settings("settings", "Settings", Icons.Default.Settings),
}

/**
 * App shell.
 *
 * Two destinations for now. The voice console becomes the start destination once the
 * agent can actually run a task; until then, shipping a command box that does nothing
 * would be worse than not shipping one.
 */
@Composable
fun NexusApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Standard bottom-nav behaviour: switching tabs shouldn't
                                // grow the back stack, and returning to a tab should
                                // restore where you were in it.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Task.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Destination.Task.route) { TaskScreen() }
            composable(Destination.History.route) { HistoryScreen() }
            composable(Destination.Setup.route) { OnboardingScreen() }
            composable(Destination.Perception.route) { PerceptionDebugScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
