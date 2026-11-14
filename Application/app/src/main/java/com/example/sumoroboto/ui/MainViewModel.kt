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
import com.example.sumoroboto.data.model.HistoryItemDto
import com.example.sumoroboto.data.model.RobotMode
import com.example.sumoroboto.data.model.SessionSummaryDto
import com.example.sumoroboto.data.model.TelemetryData
import com.example.sumoroboto.data.model.TelemetryPointDto
import com.example.sumoroboto.data.network.NetworkClient
import com.example.sumoroboto.data.settings.AppAccentColor
import com.example.sumoroboto.data.settings.AppSettingsManager
import com.example.sumoroboto.data.settings.AppThemeMode
import com.example.sumoroboto.ui.components.GraphMetric
import com.example.sumoroboto.ui.navigation.AppFlowState
import com.example.sumoroboto.ui.navigation.ScreenDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class HistoryFilterType {
    ALL,
    COLD,
    HOT,
    SESSIONS
}

data class HistoryUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val filterType: HistoryFilterType = HistoryFilterType.ALL,
    val historyItems: List<HistoryItemDto> = emptyList(),
    val sessions: List<SessionSummaryDto> = emptyList(),
    val selectedSessionPoints: List<TelemetryPointDto> = emptyList(),
    val livePoints: List<TelemetryPointDto> = emptyList(),
    val selectedSessionId: Int? = null,
    val activeMetric: GraphMetric = GraphMetric.BATTERY,
    val isAppGenerating: Boolean = false,
    val isServerSimulating: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

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

    private val _currentDestination = MutableStateFlow(ScreenDestination.TELEMETRY)
    val currentDestination: StateFlow<ScreenDestination> = _currentDestination.asStateFlow()

    val loggingSession: StateFlow<LoggingSessionState> = csvLogger.sessionState
    val sessionRecorderState: StateFlow<SessionRecorderState> = sessionRecorder.state
    val backendBaseUrl: StateFlow<String> = NetworkClient.baseUrlFlow

    // History, Analytics & In-App Generator State
    private val _historyUiState = MutableStateFlow(HistoryUiState())
    val historyUiState: StateFlow<HistoryUiState> = _historyUiState.asStateFlow()

    private val livePointBuffer = ArrayList<TelemetryPointDto>()
    private var inAppGeneratorJob: Job? = null
    private val telemetryStartTimeMs = System.currentTimeMillis()

    init {
        // Collect telemetry stream
        viewModelScope.launch {
            bluetoothManager.telemetryFlow.collect { data ->
                // When in-app generator is actively generating, avoid overriding live telemetry & battery
                if (_historyUiState.value.isAppGenerating) {
                    return@collect
                }

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
                appendLivePoint(
                    TelemetryPointDto(
                        relTimeMs = System.currentTimeMillis() - telemetryStartTimeMs,
                        vbat = data.vBat,
                        distL = data.distL,
                        distR = data.distR,
                        lineL = if (data.lineL) 1 else 0,
                        lineR = if (data.lineR) 1 else 0,
                        pwmL = _currentPwmL.value,
                        pwmR = _currentPwmR.value
                    )
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

    // --- Live Ring Buffer & In-App Generator ---

    private fun appendLivePoint(point: TelemetryPointDto) {
        synchronized(livePointBuffer) {
            livePointBuffer.add(point)
            if (livePointBuffer.size > 120) {
                livePointBuffer.removeAt(0)
            }
            _historyUiState.value = _historyUiState.value.copy(
                livePoints = ArrayList(livePointBuffer)
            )
        }
    }

    /**
     * In-App Telemetry Generator matching the backend simulator:
     * Generates realistic telemetry (VBat: 6.0-8.4V, Dist: 2-80cm, PWM: 0-255).
     * Automatically starts a new session on launch, and upon stopping, uploads
     * the entire session and its points to the backend / SQLite database.
     */
    fun toggleInAppTelemetryGenerator() {
        if (inAppGeneratorJob != null) {
            inAppGeneratorJob?.cancel()
            inAppGeneratorJob = null
            _historyUiState.value = _historyUiState.value.copy(
                isAppGenerating = false,
                statusMessage = "Munkamenet lezárása és feltöltése a szerverre..."
            )

            // Stop and upload session to backend
            if (sessionRecorder.state.value.isRecording) {
                val endVbat = _telemetry.value.vBat
                sessionRecorder.stopAndUploadSession(
                    endVbat = endVbat,
                    notes = "In-App Generált Futam (MATCH)"
                ) { success, msg ->
                    viewModelScope.launch {
                        delay(250)
                        fetchHistory()
                        _historyUiState.value = _historyUiState.value.copy(
                            statusMessage = if (success) "Új munkamenet sikeresen mentve az adatbázisba!" else "Feltöltési hiba: $msg",
                            isError = !success
                        )
                    }
                }
            } else {
                _historyUiState.value = _historyUiState.value.copy(
                    statusMessage = "In-app generátor leállítva."
                )
            }
        } else {
            val initialVbat = 8.35f
            sessionRecorder.startSession(sessionType = "MATCH", startVbat = initialVbat)

            _historyUiState.value = _historyUiState.value.copy(
                isAppGenerating = true,
                statusMessage = "In-app generátor fut (Új Session rögzítése)..."
            )
            inAppGeneratorJob = viewModelScope.launch(Dispatchers.Default) {
                val random = java.util.Random()
                var simOcv = initialVbat
                val simStart = System.currentTimeMillis()

                var combatTicksLeft = 0
                var currentDistL = 50
                var currentDistR = 50
                var currentPwmL = 110
                var currentPwmR = 110
                var currentLineL = false
                var currentLineR = false

                while (isActive) {
                    delay(200) // 5 Hz update rate

                    // Sumo match simulation cycle:
                    // 1) Active combat / pushing against opponent: close distance, full motor throttle
                    // 2) Roaming / Search / Idle: moderate to low throttle, obstacle far away
                    if (combatTicksLeft > 0) {
                        combatTicksLeft--
                        currentDistL = random.nextInt(12) + 3 // 3-14 cm (close combat)
                        currentDistR = random.nextInt(12) + 3
                        currentPwmL = random.nextInt(46) + 210 // 210-255 (full push power)
                        currentPwmR = random.nextInt(46) + 210
                        currentLineL = random.nextFloat() < 0.08f // Line edge encounter during struggle
                        currentLineR = random.nextFloat() < 0.08f
                    } else {
                        // 15% probability per tick to detect opponent and engage in a 12-25 tick (2.4 - 5.0 s) fight
                        if (random.nextFloat() < 0.15f) {
                            combatTicksLeft = random.nextInt(14) + 12
                        }

                        val isIdling = random.nextFloat() < 0.20f
                        if (isIdling) {
                            // Paused / orienting
                            currentPwmL = 0
                            currentPwmR = 0
                            currentDistL = random.nextInt(25) + 40
                            currentDistR = random.nextInt(25) + 40
                            currentLineL = false
                            currentLineR = false
                        } else {
                            // Gentle cruising / search
                            currentPwmL = random.nextInt(35) + 90
                            currentPwmR = random.nextInt(35) + 90
                            currentDistL = random.nextInt(35) + 25
                            currentDistR = random.nextInt(35) + 25
                            currentLineL = random.nextFloat() < 0.02f
                            currentLineR = random.nextFloat() < 0.02f
                        }
                    }

                    val isCombat = combatTicksLeft > 0 || currentDistL <= 25 || currentDistR <= 25 || (currentPwmL + currentPwmR) > 380 || currentLineL || currentLineR
                    val isHeavyCombat = (currentDistL <= 10 || currentDistR <= 10) && (currentPwmL + currentPwmR) > 420

                    // Dynamic load-based battery discharge rate
                    val drainPerTick = when {
                        isHeavyCombat -> 0.0006f + random.nextFloat() * 0.0004f // Heavy fight drain (~0.003-0.005 V/s)
                        isCombat -> 0.00035f + random.nextFloat() * 0.00025f   // Active maneuver (~0.0018-0.003 V/s)
                        (currentPwmL == 0 && currentPwmR == 0) -> 0.000015f     // Idle/standby (~0.000075 V/s, steady %)
                        else -> 0.00004f + random.nextFloat() * 0.00003f       // Gentle roam (~0.0002-0.00035 V/s)
                    }

                    simOcv = (simOcv - drainPerTick).coerceIn(6.4f, 8.4f)

                    // Load sag (IR voltage drop under motor load)
                    val motorLoad = ((currentPwmL + currentPwmR) / 510f).coerceIn(0f, 1f)
                    val irDrop = if (isCombat) (motorLoad * 0.035f) else (motorLoad * 0.012f)
                    val terminalVbat = kotlin.math.round((simOcv - irDrop) * 100f) / 100f

                    val simulatedTelemetry = TelemetryData(
                        distL = currentDistL,
                        distR = currentDistR,
                        lineL = currentLineL,
                        lineR = currentLineR,
                        vBat = terminalVbat,
                        battery = BatteryState.fromVoltage(terminalVbat),
                        timestamp = System.currentTimeMillis()
                    )

                    _telemetry.value = simulatedTelemetry
                    _batteryState.value = simulatedTelemetry.battery
                    _currentPwmL.value = currentPwmL
                    _currentPwmR.value = currentPwmR

                    // Feed session recorder if an active match or free-roam session is running
                    sessionRecorder.recordPoint(
                        vbat = terminalVbat,
                        distL = currentDistL,
                        distR = currentDistR,
                        lineL = if (currentLineL) 1 else 0,
                        lineR = if (currentLineR) 1 else 0,
                        pwmL = currentPwmL,
                        pwmR = currentPwmR
                    )

                    val pt = TelemetryPointDto(
                        relTimeMs = System.currentTimeMillis() - simStart,
                        vbat = terminalVbat,
                        distL = currentDistL,
                        distR = currentDistR,
                        lineL = if (currentLineL) 1 else 0,
                        lineR = if (currentLineR) 1 else 0,
                        pwmL = currentPwmL,
                        pwmR = currentPwmR
                    )
                    appendLivePoint(pt)
                }
            }
        }
    }

    // --- History, Filter & Graph Queries ---

    fun fetchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _historyUiState.value = _historyUiState.value.copy(isLoading = true, statusMessage = null)
            try {
                val typeParam = when (_historyUiState.value.filterType) {
                    HistoryFilterType.ALL -> "all"
                    HistoryFilterType.COLD -> "cold"
                    HistoryFilterType.HOT -> "hot"
                    HistoryFilterType.SESSIONS -> "all"
                }
                val q = _historyUiState.value.searchQuery.ifBlank { null }
                val historyRes = NetworkClient.api.getHistory(type = typeParam, query = q)
                val sessionsRes = NetworkClient.api.getSessions()

                val items = if (historyRes.isSuccessful) historyRes.body()?.items ?: emptyList() else emptyList()
                val sessions = if (sessionsRes.isSuccessful) sessionsRes.body() ?: emptyList() else emptyList()

                _historyUiState.value = _historyUiState.value.copy(
                    isLoading = false,
                    historyItems = items,
                    sessions = sessions,
                    statusMessage = "Szinkronizálva (${items.size} napló, ${sessions.size} munkamenet).",
                    isError = false
                )
            } catch (e: Exception) {
                _historyUiState.value = _historyUiState.value.copy(
                    isLoading = false,
                    statusMessage = "Szerverkapcsolat nem érhető el: ${e.localizedMessage ?: "Időtúllépés"}",
                    isError = true
                )
            }
        }
    }

    fun selectSessionForGraph(sessionId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _historyUiState.value = _historyUiState.value.copy(isLoading = true)
            try {
                val res = NetworkClient.api.getSessionPoints(sessionId)
                if (res.isSuccessful && res.body() != null) {
                    val pts = res.body()!!.points
                    _historyUiState.value = _historyUiState.value.copy(
                        isLoading = false,
                        selectedSessionId = sessionId,
                        selectedSessionPoints = pts,
                        statusMessage = "Session #$sessionId betöltve (${pts.size} pont a grafikonon).",
                        isError = false
                    )
                } else {
                    _historyUiState.value = _historyUiState.value.copy(
                        isLoading = false,
                        statusMessage = "Hiba a pontok lekérésekor (HTTP ${res.code()}).",
                        isError = true
                    )
                }
            } catch (e: Exception) {
                _historyUiState.value = _historyUiState.value.copy(
                    isLoading = false,
                    statusMessage = "Hálózati hiba: ${e.localizedMessage}",
                    isError = true
                )
            }
        }
    }

    fun clearSelectedSession() {
        _historyUiState.value = _historyUiState.value.copy(
            selectedSessionId = null,
            selectedSessionPoints = emptyList(),
            statusMessage = "Visszatérés az élő telemetria görbéhez."
        )
    }

    fun setHistoryFilter(filter: HistoryFilterType) {
        _historyUiState.value = _historyUiState.value.copy(filterType = filter)
        fetchHistory()
    }

    fun setHistorySearchQuery(query: String) {
        _historyUiState.value = _historyUiState.value.copy(searchQuery = query)
        fetchHistory()
    }

    fun setGraphMetric(metric: GraphMetric) {
        _historyUiState.value = _historyUiState.value.copy(activeMetric = metric)
    }

    fun toggleServerSimulator() {
        viewModelScope.launch(Dispatchers.IO) {
            val willStart = !_historyUiState.value.isServerSimulating
            try {
                if (willStart) {
                    NetworkClient.api.startSimulator()
                    _historyUiState.value = _historyUiState.value.copy(
                        isServerSimulating = true,
                        statusMessage = "Szerver szimulátor elindítva."
                    )
                } else {
                    NetworkClient.api.stopSimulator()
                    _historyUiState.value = _historyUiState.value.copy(
                        isServerSimulating = false,
                        statusMessage = "Szerver szimulátor leállítva."
                    )
                }
                fetchHistory()
            } catch (e: Exception) {
                _historyUiState.value = _historyUiState.value.copy(
                    statusMessage = "Szerver hiba: ${e.localizedMessage}",
                    isError = true
                )
            }
        }
    }

    fun clearRemoteDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            _historyUiState.value = _historyUiState.value.copy(isLoading = true)
            try {
                val res = NetworkClient.api.clearDatabase()
                if (res.isSuccessful) {
                    _historyUiState.value = _historyUiState.value.copy(
                        isLoading = false,
                        historyItems = emptyList(),
                        sessions = emptyList(),
                        selectedSessionId = null,
                        selectedSessionPoints = emptyList(),
                        statusMessage = "Adatbázis sikeresen kiürítve.",
                        isError = false
                    )
                } else {
                    _historyUiState.value = _historyUiState.value.copy(
                        isLoading = false,
                        statusMessage = "Törlési hiba (HTTP ${res.code()}).",
                        isError = true
                    )
                }
            } catch (e: Exception) {
                _historyUiState.value = _historyUiState.value.copy(
                    isLoading = false,
                    statusMessage = "Hiba az adatbázis törlésekor: ${e.localizedMessage}",
                    isError = true
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        inAppGeneratorJob?.cancel()
        sessionRecorder.cancelSession()
        csvLogger.stopLogging()
        bluetoothManager.cleanup()
    }
}
