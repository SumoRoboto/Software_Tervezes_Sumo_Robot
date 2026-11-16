package com.example.sumoroboto.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.sumoroboto.data.model.BatteryState
import com.example.sumoroboto.data.model.RobotMode
import com.example.sumoroboto.data.model.TelemetryData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.random.Random

class BluetoothManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        // Standard SPP UUID for HC-05 / HC-06
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val VIRTUAL_DEVICE_ADDRESS = "00:00:00:00:SUMO"
        const val VIRTUAL_DEVICE_NAME = "Virtuális Sumo Robot"
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    private val _connectionState = MutableStateFlow<BluetoothConnectionState>(BluetoothConnectionState.Disconnected)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()

    private val _telemetryFlow = MutableSharedFlow<TelemetryData>(replay = 1)
    val telemetryFlow: SharedFlow<TelemetryData> = _telemetryFlow.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceModel>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceModel>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var currentSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var tickerJob: Job? = null
    private var simulationJob: Job? = null

    // Target motor speed set by UI gestures, sampled by the ticker loop
    @Volatile
    private var targetPwmL: Int = 0

    @Volatile
    private var targetPwmR: Int = 0

    @Volatile
    private var currentMode: RobotMode = RobotMode.MANUAL

    @Volatile
    private var isSimulated: Boolean = false

    private var isReceiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val name = try {
                            device.name ?: "Unknown Device"
                        } catch (_: SecurityException) {
                            "Unknown Device"
                        }
                        val address = device.address ?: return
                        val isBonded = try {
                            device.bondState == BluetoothDevice.BOND_BONDED
                        } catch (_: SecurityException) {
                            false
                        }

                        val newModel = BluetoothDeviceModel(
                            name = name,
                            address = address,
                            isBonded = isBonded,
                            isVirtual = false
                        )

                        val current = _discoveredDevices.value.toMutableList()
                        if (current.none { it.address == address }) {
                            current.add(newModel)
                            _discoveredDevices.value = current
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    init {
        startMotorTicker()
        registerReceiver()
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(discoveryReceiver, filter)
            isReceiverRegistered = true
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Lists bonded (paired) Bluetooth devices.
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDeviceModel> {
        val list = mutableListOf<BluetoothDeviceModel>()

        // Always include the virtual simulation device for easy testing
        list.add(
            BluetoothDeviceModel(
                name = VIRTUAL_DEVICE_NAME,
                address = VIRTUAL_DEVICE_ADDRESS,
                isBonded = true,
                isVirtual = true
            )
        )

        try {
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                list.add(
                    BluetoothDeviceModel(
                        name = device.name ?: "Sumo Robot ${device.address.takeLast(5)}",
                        address = device.address,
                        isBonded = true,
                        isVirtual = false
                    )
                )
            }
        } catch (_: SecurityException) {
            // Permission not yet granted
        }

        return list
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            _discoveredDevices.value = emptyList()
            val started = adapter.startDiscovery()
            _isScanning.value = started
        } catch (_: SecurityException) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {}
        _isScanning.value = false
    }

    /**
     * Connects to a device by its MAC address.
     */
    fun connect(device: BluetoothDeviceModel) {
        disconnect()

        if (device.isVirtual || device.address == VIRTUAL_DEVICE_ADDRESS) {
            connectVirtual()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BluetoothConnectionState.Error("Bluetooth is not enabled or available")
            return
        }

        _connectionState.value = BluetoothConnectionState.Connecting(device.name)

        scope.launch(Dispatchers.IO) {
            try {
                @SuppressLint("MissingPermission")
                val bluetoothDevice: BluetoothDevice = adapter.getRemoteDevice(device.address)

                // Cancel discovery before connecting as it slows down connection
                @SuppressLint("MissingPermission")
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }

                @SuppressLint("MissingPermission")
                val socket = bluetoothDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                currentSocket = socket
                outputStream = socket.outputStream
                isSimulated = false

                _connectionState.value = BluetoothConnectionState.Connected(
                    deviceName = device.name,
                    address = device.address
                )

                // Start reader loop
                startReaderLoop(socket)
            } catch (e: Exception) {
                disconnect()
                _connectionState.value = BluetoothConnectionState.Error(
                    "Connection failed: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    fun connectVirtual() {
        disconnect()
        isSimulated = true
        _connectionState.value = BluetoothConnectionState.Connected(
            deviceName = VIRTUAL_DEVICE_NAME,
            address = VIRTUAL_DEVICE_ADDRESS
        )
        startSimulationLoop()
    }

    /**
     * Dedicated Coroutine running on Dispatchers.IO that continuously reads lines
     * from the incoming Bluetooth stream and parses telemetry packets.
     */
    private fun startReaderLoop(socket: BluetoothSocket) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val telemetry = ProtocolParser.parseTelemetry(line)
                    if (telemetry != null) {
                        _telemetryFlow.emit(telemetry)
                    }
                }
            } catch (_: Exception) {
                // Connection broken
            } finally {
                withContext(Dispatchers.Main) {
                    if (_connectionState.value is BluetoothConnectionState.Connected) {
                        disconnect()
                        _connectionState.value = BluetoothConnectionState.Disconnected
                    }
                }
            }
        }
    }

    /**
     * Simulated telemetry generator for verification and standalone demonstration.
     */
    private fun startSimulationLoop() {
        simulationJob?.cancel()
        simulationJob = scope.launch(Dispatchers.Default) {
            var simOcv = 8.25f
            var distanceL = 45
            var distanceR = 48
            var lineL = false
            var lineR = false

            while (isActive && isSimulated) {
                delay(40) // ~25 Hz telemetry stream

                val moving = (targetPwmL != 0 || targetPwmR != 0)
                val motorLoad = (Math.abs(targetPwmL) + Math.abs(targetPwmR)) / 510f
                val isHighLoad = motorLoad > 0.6f

                // Realistic dynamic battery discharge rate based on motor load
                val drain = when {
                    isHighLoad -> 0.00008f + Random.nextFloat() * 0.00003f // Active high-load drive (~0.002-0.0028 V/s)
                    moving -> 0.00003f + Random.nextFloat() * 0.00002f     // Gentle drive (~0.00075-0.0012 V/s)
                    else -> 0.000002f                                      // Idle/standby (~0.00005 V/s, steady %)
                }

                simOcv = (simOcv - drain).coerceAtLeast(6.2f)

                // IR drop under motor load
                val irDrop = if (moving) motorLoad * 0.03f else 0f
                val cleanVbat = kotlin.math.round((simOcv - irDrop) * 100f) / 100f

                // Simulate sensor dynamics based on motor movement
                if (moving) {
                    distanceL = (distanceL + Random.nextInt(-3, 4)).coerceIn(8, 70)
                    distanceR = (distanceR + Random.nextInt(-3, 4)).coerceIn(8, 70)
                    // Occasionally trigger line sensor if driving hard
                    lineL = Random.nextInt(100) > 96
                    lineR = Random.nextInt(100) > 96
                } else {
                    lineL = false
                    lineR = false
                }

                val simulatedData = TelemetryData(
                    distL = distanceL,
                    distR = distanceR,
                    lineL = lineL,
                    lineR = lineR,
                    vBat = cleanVbat,
                    battery = BatteryState.fromVoltage(cleanVbat),
                    timestamp = System.currentTimeMillis()
                )
                _telemetryFlow.emit(simulatedData)
            }
        }
    }

    /**
     * Dedicated ticker loop running at 40 Hz (25 ms) that rate-limits outgoing motor commands
     * to prevent Bluetooth queue flooding.
     */
    private fun startMotorTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.IO) {
            var lastSentL = Int.MIN_VALUE
            var lastSentR = Int.MIN_VALUE

            while (isActive) {
                delay(25) // 40 Hz (within 30-50 Hz specification)

                if (_connectionState.value is BluetoothConnectionState.Connected) {
                    val currentL = targetPwmL
                    val currentR = targetPwmR

                    // Send packet if motors are active or if transitioning to stop (0,0)
                    if (currentL != 0 || currentR != 0 || lastSentL != 0 || lastSentR != 0) {
                        val packet = ProtocolParser.createMotorCommand(currentL, currentR)
                        writeRaw(packet)
                        lastSentL = currentL
                        lastSentR = currentR
                    }
                }
            }
        }
    }

    /**
     * Updates target motor speeds. Touch gestures should call this instead of directly writing to Bluetooth.
     */
    fun updateTargetMotorSpeed(pwmL: Int, pwmR: Int) {
        targetPwmL = pwmL
        targetPwmR = pwmR
    }

    /**
     * Sets robot mode (MANUAL or AUTO) and sends command.
     */
    fun setMode(mode: RobotMode) {
        currentMode = mode
        val packet = ProtocolParser.createModeCommand(mode)
        scope.launch(Dispatchers.IO) {
            writeRaw(packet)
        }
    }

    /**
     * Triggers emergency stop immediately: cuts target speeds and sends STOP\n packet.
     */
    fun emergencyStop() {
        targetPwmL = 0
        targetPwmR = 0
        val packet = ProtocolParser.createStopCommand()
        scope.launch(Dispatchers.IO) {
            writeRaw(packet)
        }
    }

    /**
     * Thread-safe raw Bluetooth transmission.
     */
    private fun writeRaw(command: String) {
        if (isSimulated) {
            // Simulated device silently accepts command
            return
        }
        try {
            val bytes = command.toByteArray(StandardCharsets.UTF_8)
            synchronized(this) {
                outputStream?.write(bytes)
                outputStream?.flush()
            }
        } catch (_: Exception) {
            // Write failed; socket may be broken
        }
    }

    /**
     * Cleanly closes socket and stream resources.
     */
    fun disconnect() {
        readJob?.cancel()
        readJob = null
        simulationJob?.cancel()
        simulationJob = null

        isSimulated = false
        targetPwmL = 0
        targetPwmR = 0

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            currentSocket?.close()
        } catch (_: Exception) {}
        currentSocket = null

        _connectionState.value = BluetoothConnectionState.Disconnected
    }

    fun cleanup() {
        disconnect()
        tickerJob?.cancel()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }
}
