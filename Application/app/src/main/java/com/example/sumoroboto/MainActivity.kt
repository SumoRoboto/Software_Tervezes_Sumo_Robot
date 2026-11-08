package com.example.sumoroboto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sumoroboto.ui.MainScreen
import com.example.sumoroboto.ui.MainViewModel
import com.example.sumoroboto.ui.navigation.AppFlowState
import com.example.sumoroboto.ui.screens.ConnectionScreen
import com.example.sumoroboto.ui.screens.SplashScreen
import com.example.sumoroboto.ui.theme.SumoRobotoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val accentColor by viewModel.accentColor.collectAsState()
            val appFlowState by viewModel.appFlowState.collectAsState()

            SumoRobotoTheme(
                themeMode = themeMode,
                accentColor = accentColor
            ) {
                Crossfade(targetState = appFlowState, label = "AppFlowTransition") { state ->
                    when (state) {
                        AppFlowState.SPLASH -> {
                            SplashScreen(
                                onFinished = {
                                    viewModel.setAppFlowState(AppFlowState.CONNECTION)
                                }
                            )
                        }
                        AppFlowState.CONNECTION -> {
                            val connectionState by viewModel.connectionState.collectAsState()
                            val bondedDevices by viewModel.bondedDevices.collectAsState()
                            val discoveredDevices by viewModel.discoveredDevices.collectAsState()
                            val isScanning by viewModel.isScanning.collectAsState()

                            ConnectionScreen(
                                connectionState = connectionState,
                                bondedDevices = bondedDevices,
                                discoveredDevices = discoveredDevices,
                                isScanning = isScanning,
                                onConnectDevice = { viewModel.connectDevice(it) },
                                onStartScan = { viewModel.startBluetoothScan() },
                                onRefresh = { viewModel.refreshDevices() }
                            )
                        }
                        AppFlowState.MAIN -> {
                            MainScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}