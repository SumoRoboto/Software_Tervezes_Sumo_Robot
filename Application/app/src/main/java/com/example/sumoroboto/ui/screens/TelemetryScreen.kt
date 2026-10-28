package com.example.sumoroboto.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sumoroboto.data.logging.LoggingSessionState
import com.example.sumoroboto.data.logging.SessionRecorderState
import com.example.sumoroboto.data.model.TelemetryData
import com.example.sumoroboto.ui.components.DistanceSensorCard
import com.example.sumoroboto.ui.components.LineSensorCard
import java.util.Locale

@Composable
fun TelemetryScreen(
    telemetry: TelemetryData,
    loggingState: LoggingSessionState,
    sessionRecorderState: SessionRecorderState,
    onToggleLogging: () -> Unit,
    onShareCsv: () -> Unit,
    onStartCloudSession: (String) -> Unit,
    onStopAndUploadCloudSession: (String) -> Unit,
    onCancelCloudSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var selectedSessionType by remember { mutableStateOf("MATCH") }
    var notesText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Live Distance Sensors
        Text(
            text = "Distance Sensors (Front Cones)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DistanceSensorCard(
                label = "Left Distance",
                distanceCm = telemetry.distL,
                modifier = Modifier.weight(1f)
            )
            DistanceSensorCard(
                label = "Right Distance",
                distanceCm = telemetry.distR,
                modifier = Modifier.weight(1f)
            )
        }

        // Section: Border / Line Sensors
        Text(
            text = "Dohyo Line Edge Sensors",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LineSensorCard(
                label = "Line Sensor Left",
                isBorderDetected = telemetry.lineL,
                modifier = Modifier.weight(1f)
            )
            LineSensorCard(
                label = "Line Sensor Right",
                isBorderDetected = telemetry.lineR,
                modifier = Modifier.weight(1f)
            )
        }

        // Section: Battery & Power Telemetry
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = "Power Subsystem (2S LiPo)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Measured Voltage",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f V", telemetry.vBat),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Estimated Remaining",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${telemetry.battery.percentage} %",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (telemetry.battery.isCritical) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Section: Session Data Logger Panel
        Text(
            text = "Data Logging & CSV Export",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Recording status indicator & timer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loggingState.isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RECORDING ACTIVE",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE53935),
                                fontSize = 13.sp
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LOGGER IDLE",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val minutes = loggingState.elapsedSeconds / 60
                        val seconds = loggingState.elapsedSeconds % 60
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Sample counter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Recorded Samples:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${loggingState.sampleCount} rows",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Start/Stop and Export Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onToggleLogging,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (loggingState.isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (loggingState.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (loggingState.isRecording) "Stop Logging" else "Start Logging"
                        )
                    }

                    OutlinedButton(
                        onClick = onShareCsv,
                        modifier = Modifier.weight(1f),
                        enabled = loggingState.sampleCount > 0 || loggingState.latestFile != null,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Export CSV")
                    }
                }

                if (loggingState.latestFile != null) {
                    Text(
                        text = "File: ${loggingState.latestFile.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Section: FastAPI Cloud Session Sync
        Text(
            text = "FastAPI Backend Session Sync",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header & status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (sessionRecorderState.isRecording) Color(0xFFE53935)
                                    else if (sessionRecorderState.isUploading) Color(0xFFFFA000)
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (sessionRecorderState.isRecording) "RECORDING (${sessionRecorderState.sessionType})"
                            else if (sessionRecorderState.isUploading) "UPLOADING..."
                            else "SESSION IDLE",
                            fontWeight = FontWeight.Bold,
                            color = if (sessionRecorderState.isRecording) Color(0xFFE53935)
                            else if (sessionRecorderState.isUploading) Color(0xFFFFA000)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val minutes = sessionRecorderState.elapsedSeconds / 60
                        val seconds = sessionRecorderState.elapsedSeconds % 60
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Session type chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Type:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilterChip(
                        selected = selectedSessionType == "MATCH",
                        onClick = {
                            if (!sessionRecorderState.isRecording) selectedSessionType = "MATCH"
                        },
                        label = { Text("MATCH (Verseny)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.SportsKabaddi,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        enabled = !sessionRecorderState.isRecording
                    )
                    FilterChip(
                        selected = selectedSessionType == "FREE_ROAM",
                        onClick = {
                            if (!sessionRecorderState.isRecording) selectedSessionType = "FREE_ROAM"
                        },
                        label = { Text("FREE_ROAM") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Timeline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        enabled = !sessionRecorderState.isRecording
                    )
                }

                // Points buffered counter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Buffered Telemetry Points:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${sessionRecorderState.pointCount} points (50 Hz)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (sessionRecorderState.isRecording) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Start Battery Voltage:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f V", sessionRecorderState.startVbat),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Notes text field
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Session Notes (opcionális jegyzet)") },
                    placeholder = { Text("pl. Match 1 vs Red Dragon") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    enabled = !sessionRecorderState.isUploading
                )

                // Action buttons
                if (!sessionRecorderState.isRecording) {
                    Button(
                        onClick = { onStartCloudSession(selectedSessionType) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !sessionRecorderState.isUploading
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Start $selectedSessionType Session")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onStopAndUploadCloudSession(notesText) },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !sessionRecorderState.isUploading
                        ) {
                            if (sessionRecorderState.isUploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Uploading...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop & Upload")
                            }
                        }

                        OutlinedButton(
                            onClick = onCancelCloudSession,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !sessionRecorderState.isUploading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Discard")
                        }
                    }
                }

                // Upload Feedback Banner
                if (sessionRecorderState.lastUploadMessage != null) {
                    val isSuccess = sessionRecorderState.lastUploadSuccess == true
                    val isError = sessionRecorderState.lastUploadSuccess == false

                    val bannerBg = if (isSuccess) Color(0xFFE8F5E9)
                    else if (isError) Color(0xFFFFEBEE)
                    else MaterialTheme.colorScheme.surfaceVariant

                    val bannerBorder = if (isSuccess) Color(0xFF4CAF50)
                    else if (isError) Color(0xFFE53935)
                    else MaterialTheme.colorScheme.outlineVariant

                    val bannerContentColor = if (isSuccess) Color(0xFF2E7D32)
                    else if (isError) Color(0xFFC62828)
                    else MaterialTheme.colorScheme.onSurfaceVariant

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, bannerBorder),
                        colors = CardDefaults.cardColors(containerColor = bannerBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle
                                else if (isError) Icons.Default.Error
                                else Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = bannerContentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = sessionRecorderState.lastUploadMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = bannerContentColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
