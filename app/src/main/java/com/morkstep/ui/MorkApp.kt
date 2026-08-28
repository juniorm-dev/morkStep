package com.morkstep.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object Routes {
    const val HOME = "home"
    const val WORKOUT = "workout"
    const val CONFIG = "config"
    const val HISTORY = "history"
}

@Composable
fun MorkApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()

    val bottomTabs = listOf(
        Triple(Routes.HOME, "Home", Icons.Filled.Home),
        Triple(Routes.HISTORY, "History", Icons.AutoMirrored.Filled.List),
        Triple(Routes.CONFIG, "Settings", Icons.Filled.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomTabs.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                        onClick = {
                            // Deterministic tab switch: reset the back stack to just
                            // this tab (no saveState/restoreState — restoring can
                            // land on a stale saved destination, e.g. Settings when
                            // the user taps Home).
                            navController.navigate(route) {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    profiles = profiles,
                    activeId = activeId,
                    onSelectProfile = viewModel::selectProfile,
                    onStart = {
                        viewModel.startWorkout()
                        navController.navigate(Routes.WORKOUT)
                    },
                    onConfig = { navController.navigate(Routes.CONFIG) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.WORKOUT) {
                val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
                activeProfile?.let { profile ->
                    WorkoutScreen(
                        live = live,
                        profile = profile,
                        onEnd = {
                            viewModel.endWorkout()
                            navController.popBackStack()
                        },
                        onStop = {
                            viewModel.discardWorkout()
                            navController.popBackStack()
                        },
                    )
                }
            }
            composable(Routes.CONFIG) {
                ConfigScreen(
                    profiles = profiles,
                    selectedId = activeId,
                    onSelect = viewModel::selectProfile,
                    onSave = viewModel::updateProfile,
                    onNewProfile = viewModel::newProfileFromActive,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen()
            }
        }
    }
}