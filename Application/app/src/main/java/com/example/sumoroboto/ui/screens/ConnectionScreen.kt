package com.example.sumoroboto.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sumoroboto.data.bluetooth.BluetoothConnectionState
import com.example.sumoroboto.data.bluetooth.BluetoothDeviceModel

@Composable
fun ConnectionScreen(
    connectionState: BluetoothConnectionState,
    bondedDevices: List<BluetoothDeviceModel>,
    discoveredDevices: List<BluetoothDeviceModel>,
    isScanning: Boolean,
    onConnectDevice: (BluetoothDeviceModel) -> Unit,
    onStartScan: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var hasPermissions by remember {
        mutableStateOf(checkBluetoothPermissions(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) {
            onRefresh()
        }
    }

    LaunchedEffect(Unit) {
        hasPermissions = checkBluetoothPermissions(context)
        if (!hasPermissions) {
            val permissions = getRequiredPermissions()
            permissionLauncher.launch(permissions)
        } else {
            onRefresh()
        }
    }

    // Combine bonded and discovered lists (without duplicates)
    val allDevices = remember(bondedDevices, discoveredDevices) {
        val set = mutableSetOf<String>()
        val combined = mutableListOf<BluetoothDeviceModel>()

        // Put discovered devices first, then bonded
        discoveredDevices.forEach {
            if (set.add(it.address)) combined.add(it)
        }
        bondedDevices.forEach {
            if (set.add(it.address)) combined.add(it)
        }
        combined
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Clean Header with Single Unified Scan Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Robots",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${allDevices.size} available",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Single Prominent, Modern Scan Button (no duplicate refresh button)
            Button(
                onClick = onStartScan,
                enabled = !isScanning,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Permission Card if not granted
        if (!hasPermissions) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFB74D)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Bluetooth permission required",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFE0B2)
                        )
                    }
                    Button(
                        onClick = { permissionLauncher.launch(getRequiredPermissions()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Grant", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Device List
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (allDevices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No robots detected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onStartScan,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Start Scanning", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(allDevices) { device ->
                        val isConnectingThis = (connectionState is BluetoothConnectionState.Connecting && connectionState.deviceName == device.name)
                        DeviceConnectionCard(
                            device = device,
                            isConnecting = isConnectingThis,
                            onConnect = { onConnectDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceConnectionCard(
    device: BluetoothDeviceModel,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    val isVirtual = device.isVirtual
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isConnecting) { onConnect() },
        border = BorderStroke(
            1.dp,
            if (isVirtual) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isVirtual)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVirtual) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isVirtual) Icons.Default.SmartToy else Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (device.isBonded && !isVirtual) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PAIRED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = device.address,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onConnect,
                enabled = !isConnecting,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun checkBluetoothPermissions(context: android.content.Context): Boolean {
    val permissions = getRequiredPermissions()
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
}
