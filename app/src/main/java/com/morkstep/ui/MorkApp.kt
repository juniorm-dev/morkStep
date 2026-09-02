package com.morkstep.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import com.morkstep.data.isBaselineProfile
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import android.os.Build

object Routes {
    const val HOME = "home"
    const val WORKOUT = "workout"
    const val CONFIG = "config"
    const val HISTORY = "history"
}

@Suppress("FunctionName")
@Composable
fun MorkApp(viewModel: MainViewModel) {
    val context = LocalContext.current.applicationContext
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val simulated by viewModel.simulated.collectAsStateWithLifecycle()
    val useWearHr by viewModel.useWearHr.collectAsStateWithLifecycle()
    val wearVibrate by viewModel.wearVibrate.collectAsStateWithLifecycle()
    val hcBackfillHr by viewModel.hcBackfillHr.collectAsStateWithLifecycle()
    val hcGranted by viewModel.hcGranted.collectAsStateWithLifecycle()
    val sensorNote by viewModel.sensorNote.collectAsStateWithLifecycle()
    val locationGranted by viewModel.locationGranted.collectAsStateWithLifecycle()
    val bluetoothGranted by viewModel.bluetoothGranted.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val savedProfileName by viewModel.savedProfileName.collectAsStateWithLifecycle()
    val baselineCreatedMessage by viewModel.baselineCreatedMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val transferMessage by viewModel.transferMessage.collectAsStateWithLifecycle()

    // Storage Access Framework: pick a destination (export) / source (import).
    val createProfileDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportProfiles) }
    val openProfileDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importProfiles) }
    val createWorkoutDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportWorkouts) }
    val openWorkoutDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importWorkouts) }

    fun launchProfileExport() {
        createProfileDoc.launch("morkStep-profiles-${System.currentTimeMillis()}.json")
    }
    fun launchProfileImport() {
        openProfileDoc.launch(arrayOf("application/json"))
    }
    fun launchHistoryExport() {
        createWorkoutDoc.launch("morkStep-history-${System.currentTimeMillis()}.json")
    }
    fun launchHistoryImport() {
        openWorkoutDoc.launch(arrayOf("application/json"))
    }

    // After saving a profile: confirm with a snackbar and return to Home.
    LaunchedEffect(savedProfileName) {
        val name = savedProfileName ?: return@LaunchedEffect
        navController.navigate(Routes.HOME) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
        snackbarHostState.showSnackbar("Profile \"$name\" saved")
        viewModel.consumeSavedProfile()
    }

    // After a backup/restore: confirm with a snackbar and stop showing it.
    LaunchedEffect(transferMessage) {
        val msg = transferMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeTransferMessage()
    }

    // After a baseline workout ends: go to Settings and confirm the baseline
    // was created (the calibrated profile is already saved, ready to clone).
    // Manual early-stop and natural finish both land here.
    LaunchedEffect(baselineCreatedMessage) {
        val msg = baselineCreatedMessage ?: return@LaunchedEffect
        if (currentDestination?.route == Routes.WORKOUT) {
            navController.navigate(Routes.CONFIG) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeBaselineCreatedMessage()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    // Health Connect's own permission screen (a system activity, not a runtime prompt).
    // The library's 1.1.0 permission constants are internal; "android.permission.health.
    // READ_HEART_RATE" is the stable manifest string for both the manifest and the request.
    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) {
        viewModel.refreshHealthConnectState()
    }
    val requestHealthConnectPermission = {
        healthConnectPermissionLauncher.launch(
            setOf("android.permission.health.READ_HEART_RATE")
        )
    }

    val requestPermissions = {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    val bottomTabs = listOf(
        Triple(Routes.HOME, "Home", Icons.Filled.Home),
        Triple(Routes.HISTORY, "History", Icons.AutoMirrored.Filled.List),
        Triple(Routes.CONFIG, "Settings", Icons.Filled.Settings),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                bottomTabs.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                        onClick = {
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
                    workoutActive = live.running,
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
                activeProfile?.let { profile ->
                    WorkoutScreen(
                        live = live,
                        profile = profile,
                        simulated = simulated,
                                            onEnd = {
                        viewModel.endWorkout()
                        // Baseline: the finish event above returns Home itself.
                        if (activeProfile?.let { isBaselineProfile(it) } != true) {
                            navController.popBackStack()
                        }
                    },
                        onStop = {
                            viewModel.discardWorkout()
                            navController.popBackStack()
                        },
                        onTogglePause = viewModel::togglePause,
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
                    onCreateBaseline = viewModel::createBaselineProfile,
                    darkMode = darkMode,
                    onDarkModeChange = viewModel::setDarkMode,
                    simulated = simulated,
                    sensorNote = sensorNote,
                    onSimulatedChange = viewModel::setSimulatedSensors,
                    wearHr = useWearHr,
                    onWearHrChange = viewModel::setWearHr,
                    wearVibrate = wearVibrate,
                    onWearVibrateChange = viewModel::setWearVibrate,
                    hcBackfillHr = hcBackfillHr,
                    onHcBackfillChange = viewModel::setHcBackfillHr,
                    hcGranted = hcGranted,
                    onHealthConnectPermission = { requestHealthConnectPermission() },
                    onDelete = viewModel::deleteProfile,
                    onRequestPermissions = requestPermissions,
                    locationGranted = locationGranted,
                    bluetoothGranted = bluetoothGranted,
                    onExportProfiles = ::launchProfileExport,
                    onImportProfiles = ::launchProfileImport,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onExport = ::launchHistoryExport,
                    onImport = ::launchHistoryImport,
                )
            }
        }
    }
}