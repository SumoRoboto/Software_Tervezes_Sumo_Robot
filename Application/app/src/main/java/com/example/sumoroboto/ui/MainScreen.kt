package com.example.sumoroboto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.sumoroboto.data.bluetooth.BluetoothConnectionState
import com.example.sumoroboto.ui.components.DeviceSelectionSheet
import com.example.sumoroboto.ui.components.RobotNameDialog
import com.example.sumoroboto.ui.components.TopSumoAppBar
import com.example.sumoroboto.ui.navigation.ScreenDestination
import com.example.sumoroboto.ui.screens.ControlScreen
import com.example.sumoroboto.ui.screens.DohyoScreen
import com.example.sumoroboto.ui.screens.SettingsScreen
import com.example.sumoroboto.ui.screens.TelemetryScreen

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val batteryState by viewModel.batteryState.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val speedLimit by viewModel.speedLimit.collectAsState()
    val pwmL by viewModel.currentPwmL.collectAsState()
    val pwmR by viewModel.currentPwmR.collectAsState()
    val currentDestination by viewModel.currentDestination.collectAsState()
    val showDeviceDialog by viewModel.showDeviceDialog.collectAsState()
    val bondedDevices by viewModel.bondedDevices.collectAsState()
    val loggingState by viewModel.loggingSession.collectAsState()
    val sessionRecorderState by viewModel.sessionRecorderState.collectAsState()
    val backendBaseUrl by viewModel.backendBaseUrl.collectAsState()

    val robotName by viewModel.robotName.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()
    val showRobotNameDialog by viewModel.showRobotNameDialog.collectAsState()

    // Permissions Request Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.refreshDevices()
        }
    }

    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopSumoAppBar(
                robotName = robotName,
                connectionState = connectionState,
                batteryState = batteryState,
                onOpenDeviceSheet = { viewModel.openDeviceDialog() },
                onDisconnect = { viewModel.disconnectDevice() }
            )
        },
        bottomBar = {
            NavigationBar {
                ScreenDestination.values().forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination == destination,
                        onClick = { viewModel.setDestination(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.title
                            )
                        },
                        label = { Text(destination.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentDestination) {
                ScreenDestination.CONTROL -> {
                    ControlScreen(
                        robotMode = currentMode,
                        speedLimit = speedLimit,
                        pwmL = pwmL,
                        pwmR = pwmR,
                        onModeChange = { viewModel.setRobotMode(it) },
                        onSpeedLimitChange = { viewModel.setSpeedLimit(it) },
                        onJoystickMove = { x, y -> viewModel.onJoystickMove(x, y) },
                        onJoystickRelease = { viewModel.onJoystickRelease() },
                        onEmergencyStop = { viewModel.emergencyStop() }
                    )
                }
                ScreenDestination.TELEMETRY -> {
                    TelemetryScreen(
                        telemetry = telemetry,
                        loggingState = loggingState,
                        sessionRecorderState = sessionRecorderState,
                        onToggleLogging = { viewModel.toggleLogging() },
                        onShareCsv = { viewModel.shareCsv() },
                        onStartCloudSession = { sessionType -> viewModel.startCloudSession(sessionType) },
                        onStopAndUploadCloudSession = { notes -> viewModel.stopAndUploadCloudSession(notes) },
                        onCancelCloudSession = { viewModel.cancelCloudSession() }
                    )
                }
                ScreenDestination.DOHYO -> {
                    DohyoScreen(
                        telemetry = telemetry,
                        robotName = robotName
                    )
                }
                ScreenDestination.SETTINGS -> {
                    SettingsScreen(
                        currentThemeMode = themeMode,
                        currentAccentColor = accentColor,
                        robotName = robotName,
                        connectionState = connectionState,
                        backendBaseUrl = backendBaseUrl,
                        onThemeModeChange = { viewModel.setThemeMode(it) },
                        onAccentColorChange = { viewModel.setAccentColor(it) },
                        onEditRobotName = { viewModel.openRobotNameDialog() },
                        onDisconnectAndReturn = { viewModel.disconnectAndReturnToConnection() },
                        onBackendBaseUrlChange = { viewModel.setBackendBaseUrl(it) }
                    )
                }
            }
        }

        // Bluetooth device selection bottom sheet
        DeviceSelectionSheet(
            isOpen = showDeviceDialog,
            devices = bondedDevices,
            currentState = connectionState,
            onDismiss = { viewModel.closeDeviceDialog() },
            onDeviceSelected = { device -> viewModel.connectDevice(device) },
            onRefresh = { viewModel.refreshDevices() },
            onDisconnect = { viewModel.disconnectDevice() }
        )

        // Robot Naming Dialog
        RobotNameDialog(
            isOpen = showRobotNameDialog,
            initialName = robotName,
            onSaveName = { viewModel.setRobotName(it) },
            onDismiss = { viewModel.closeRobotNameDialog() }
        )
    }
}
