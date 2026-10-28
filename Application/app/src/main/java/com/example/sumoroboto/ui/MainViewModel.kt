package com.example.sumoroboto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sumoroboto.data.bluetooth.BluetoothConnectionState
import com.example.sumoroboto.data.bluetooth.BluetoothDeviceModel
import com.example.sumoroboto.data.bluetooth.BluetoothManager
import com.example.sumoroboto.data.logging.LoggingSessionState
import com.example.sumoroboto.data.logging.SessionRecorder
import com.example.sumoroboto.data.logging.SessionRecorderState
import com.example.sumoroboto.data.logging.TelemetryCsvLogger
import com.example.sumoroboto.data.model.BatteryState
import com.example.sumoroboto.data.model.RobotMode
import com.example.sumoroboto.data.model.TelemetryData
import com.example.sumoroboto.data.network.NetworkClient
import com.example.sumoroboto.data.settings.AppAccentColor
import com.example.sumoroboto.data.settings.AppSettingsManager
import com.example.sumoroboto.data.settings.AppThemeMode
import com.example.sumoroboto.ui.navigation.AppFlowState
import com.example.sumoroboto.ui.navigation.ScreenDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val settingsManager = AppSettingsManager(application.applicationContext)
    val bluetoothManager = BluetoothManager(application.applicationContext, viewModelScope)
    val csvLogger = TelemetryCsvLogger(application.applicationContext)
    val sessionRecorder = SessionRecorder(viewModelScope)

    // App Navigation Flow: SPLASH -> CONNECTION -> MAIN
    private val _appFlowState = MutableStateFlow(AppFlowState.SPLASH)
    val appFlowState: StateFlow<AppFlowState> = _appFlowState.asStateFlow()

    // Settings & Customization
    val themeMode: StateFlow<AppThemeMode> = settingsManager.themeMode
    val accentColor: StateFlow<AppAccentColor> = settingsManager.accentColor
    val robotName: StateFlow<String> = settingsManager.robotName

    // First Connection Robot Naming Dialog
    private val _showRobotNameDialog = MutableStateFlow(false)
    val showRobotNameDialog: StateFlow<Boolean> = _showRobotNameDialog.asStateFlow()

    // Bluetooth states
    val connectionState: StateFlow<BluetoothConnectionState> = bluetoothManager.connectionState
    val discoveredDevices: StateFlow<List<BluetoothDeviceModel>> = bluetoothManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = bluetoothManager.isScanning

    private val _bondedDevices = MutableStateFlow<List<BluetoothDeviceModel>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDeviceModel>> = _bondedDevices.asStateFlow()

    private val _showDeviceDialog = MutableStateFlow(false)
    val showDeviceDialog: StateFlow<Boolean> = _showDeviceDialog.asStateFlow()

    // Telemetry and Logging
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    private val _batteryState = MutableStateFlow(BatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val _currentMode = MutableStateFlow(RobotMode.MANUAL)
    val currentMode: StateFlow<RobotMode> = _currentMode.asStateFlow()

    private val _speedLimit = MutableStateFlow(0.80f) // 80% default
    val speedLimit: StateFlow<Float> = _speedLimit.asStateFlow()

    private val _joystickX = MutableStateFlow(0f)
    val joystickX: StateFlow<Float> = _joystickX.asStateFlow()

    private val _joystickY = MutableStateFlow(0f)
    val joystickY: StateFlow<Float> = _joystickY.asStateFlow()

    private val _currentPwmL = MutableStateFlow(0)
    val currentPwmL: StateFlow<Int> = _currentPwmL.asStateFlow()

    private val _currentPwmR = MutableStateFlow(0)
    val currentPwmR: StateFlow<Int> = _currentPwmR.asStateFlow()

    private val _currentDestination = MutableStateFlow(ScreenDestination.CONTROL)
    val currentDestination: StateFlow<ScreenDestination> = _currentDestination.asStateFlow()

    val loggingSession: StateFlow<LoggingSessionState> = csvLogger.sessionState
    val sessionRecorderState: StateFlow<SessionRecorderState> = sessionRecorder.state
    val backendBaseUrl: StateFlow<String> = NetworkClient.baseUrlFlow

    init {
        // Collect telemetry stream
        viewModelScope.launch {
            bluetoothManager.telemetryFlow.collect { data ->
                _telemetry.value = data
                _batteryState.value = data.battery
                csvLogger.logSample(data)
                sessionRecorder.recordPoint(
                    vbat = data.vBat,
                    distL = data.distL,
                    distR = data.distR,
                    lineL = if (data.lineL) 1 else 0,
                    lineR = if (data.lineR) 1 else 0,
                    pwmL = _currentPwmL.value,
                    pwmR = _currentPwmR.value
                )
            }
        }

        // Monitor connection state to automatically trigger robot naming and flow transition
        viewModelScope.launch {
            bluetoothManager.connectionState.collect { state ->
                if (state is BluetoothConnectionState.Connected) {
                    if (_appFlowState.value == AppFlowState.CONNECTION) {
                        _appFlowState.value = AppFlowState.MAIN
                    }
                    if (!settingsManager.hasNamedRobot.value) {
                        _showRobotNameDialog.value = true
                    }
                }
            }
        }

        refreshDevices()
    }

    fun setAppFlowState(state: AppFlowState) {
        _appFlowState.value = state
    }

    fun setThemeMode(mode: AppThemeMode) {
        settingsManager.setThemeMode(mode)
    }

    fun setAccentColor(color: AppAccentColor) {
        settingsManager.setAccentColor(color)
    }

    fun setRobotName(name: String) {
        settingsManager.setRobotName(name)
        _showRobotNameDialog.value = false
    }

    fun openRobotNameDialog() {
        _showRobotNameDialog.value = true
    }

    fun closeRobotNameDialog() {
        _showRobotNameDialog.value = false
    }

    fun refreshDevices() {
        _bondedDevices.value = bluetoothManager.getBondedDevices()
    }

    fun startBluetoothScan() {
        bluetoothManager.startDiscovery()
    }

    fun stopBluetoothScan() {
        bluetoothManager.cancelDiscovery()
    }

    fun enterSidedoor() {
        // Connect in demo/simulator mode to allow full UI exploration without hardware
        bluetoothManager.connectVirtual()
        _appFlowState.value = AppFlowState.MAIN
        if (!settingsManager.hasNamedRobot.value) {
            _showRobotNameDialog.value = true
        }
    }

    fun disconnectAndReturnToConnection() {
        bluetoothManager.disconnect()
        _appFlowState.value = AppFlowState.CONNECTION
    }

    fun openDeviceDialog() {
        refreshDevices()
        _showDeviceDialog.value = true
    }

    fun closeDeviceDialog() {
        _showDeviceDialog.value = false
    }

    fun connectDevice(device: BluetoothDeviceModel) {
        closeDeviceDialog()
        bluetoothManager.connect(device)
    }

    fun disconnectDevice() {
        bluetoothManager.disconnect()
    }

    fun setDestination(destination: ScreenDestination) {
        _currentDestination.value = destination
    }

    fun setSpeedLimit(limit: Float) {
        _speedLimit.value = limit.coerceIn(0.1f, 1.0f)
        recomputeMotorPwm(_joystickX.value, _joystickY.value)
    }

    fun setRobotMode(mode: RobotMode) {
        _currentMode.value = mode
        bluetoothManager.setMode(mode)
    }

    fun toggleMode() {
        val next = if (_currentMode.value == RobotMode.MANUAL) RobotMode.AUTO else RobotMode.MANUAL
        setRobotMode(next)
    }

    fun onJoystickMove(x: Float, y: Float) {
        _joystickX.value = x
        _joystickY.value = y
        recomputeMotorPwm(x, y)
    }

    fun onJoystickRelease() {
        _joystickX.value = 0f
        _joystickY.value = 0f
        _currentPwmL.value = 0
        _currentPwmR.value = 0
        bluetoothManager.updateTargetMotorSpeed(0, 0)
    }

    private fun recomputeMotorPwm(x: Float, y: Float) {
        val maxPwm = (255 * _speedLimit.value).roundToInt()
        val left = ((y + x) * maxPwm).roundToInt().coerceIn(-255, 255)
        val right = ((y - x) * maxPwm).roundToInt().coerceIn(-255, 255)

        _currentPwmL.value = left
        _currentPwmR.value = right

        bluetoothManager.updateTargetMotorSpeed(left, right)
    }

    fun emergencyStop() {
        _joystickX.value = 0f
        _joystickY.value = 0f
        _currentPwmL.value = 0
        _currentPwmR.value = 0
        bluetoothManager.emergencyStop()
    }

    fun toggleLogging() {
        if (loggingSession.value.isRecording) {
            csvLogger.stopLogging()
        } else {
            csvLogger.startLogging()
        }
    }

    fun shareCsv() {
        csvLogger.shareLatestCsv()
    }

    // Cloud Session Recording (FastAPI Backend)
    fun startCloudSession(sessionType: String = "MATCH") {
        sessionRecorder.startSession(sessionType = sessionType, startVbat = _telemetry.value.vBat)
    }

    fun stopAndUploadCloudSession(notes: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        sessionRecorder.stopAndUploadSession(endVbat = _telemetry.value.vBat, notes = notes, onResult = onResult)
    }

    fun cancelCloudSession() {
        sessionRecorder.cancelSession()
    }

    fun setBackendBaseUrl(newUrl: String) {
        NetworkClient.updateBaseUrl(newUrl)
    }

    override fun onCleared() {
        super.onCleared()
        sessionRecorder.cancelSession()
        csvLogger.stopLogging()
        bluetoothManager.cleanup()
    }
}
