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
import androidx.navigation.NavGraph.Companion.findStartDestination
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

    val config by viewModel.config.collectAsStateWithLifecycle()
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
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
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
                    config = config,
                    onStart = {
                        viewModel.startWorkout()
                        navController.navigate(Routes.WORKOUT)
                    },
                    onConfig = { navController.navigate(Routes.CONFIG) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.WORKOUT) {
                WorkoutScreen(
                    live = live,
                    config = config,
                    onStop = {
                        viewModel.discardWorkout()
                        navController.popBackStack()
                    },
                )
            }
            composable(Routes.CONFIG) {
                ConfigScreen(
                    config = config,
                    onSave = viewModel::saveConfig,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen()
            }
        }
    }
}