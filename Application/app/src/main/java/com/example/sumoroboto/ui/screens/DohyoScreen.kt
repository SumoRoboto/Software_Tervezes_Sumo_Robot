package com.example.sumoroboto.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sumoroboto.data.model.TelemetryData
import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DohyoScreen(
    telemetry: TelemetryData,
    robotName: String = "Mini Sumo",
    modifier: Modifier = Modifier
) {
    var gameMode by remember { mutableStateOf(false) }   // ÚJ

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Arena Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "$robotName Arena Radar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Standard 77 cm diameter with 2.5 cm white border",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!gameMode && (telemetry.lineL || telemetry.lineR)) {   // MÓDOSÍTVA: !gameMode &&
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "EDGE DETECTED",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ÚJ: Radar / Minijáték váltó
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = !gameMode,
                onClick = { gameMode = false },
                label = { Text("Radar") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = gameMode,
                onClick = { gameMode = true },
                label = { Text("Minijáték") },
                leadingIcon = {
                    Icon(Icons.Default.SportsMartialArts, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (gameMode) {
            // ÚJ: offline minijáték (nem használja a Bluetooth-ot / ViewModel-t)
            SumoMiniGame(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            // Circular Dohyo Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                DohyoCanvas(
                    telemetry = telemetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(1f)
                )
            }

            // Live Sensors Legend Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    SensorLegendItem(
                        label = "Left Distance",
                        value = if (telemetry.distL > 0) "${telemetry.distL} cm" else "--",
                        isWarning = telemetry.distL in 1..18
                    )
                    SensorLegendItem(
                        label = "Right Distance",
                        value = if (telemetry.distR > 0) "${telemetry.distR} cm" else "--",
                        isWarning = telemetry.distR in 1..18
                    )
                    SensorLegendItem(
                        label = "Left Line",
                        value = if (telemetry.lineL) "WHITE EDGE" else "CLEAR",
                        isWarning = telemetry.lineL
                    )
                    SensorLegendItem(
                        label = "Right Line",
                        value = if (telemetry.lineR) "WHITE EDGE" else "CLEAR",
                        isWarning = telemetry.lineR
                    )
                }
            }
        }
    }
}

@Composable
fun SensorLegendItem(
    label: String,
    value: String,
    isWarning: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isWarning) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DohyoCanvas(
    telemetry: TelemetryData,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val diameterPx = size.width.coerceAtMost(size.height) * 0.92f
        val pxPerCm = diameterPx / 77.0f // 77 cm diameter

        val dohyoOuterRadius = 38.5f * pxPerCm
        val whiteBorderWidth = 2.5f * pxPerCm
        val blackFieldRadius = dohyoOuterRadius - whiteBorderWidth

        // 1. Surrounding floor shadow
        drawCircle(
            color = Color(0x33000000),
            radius = dohyoOuterRadius + 8.dp.toPx(),
            center = Offset(center.x, center.y + 4.dp.toPx())
        )

        // 2. White outer border ring (Tawara / White border)
        drawCircle(
            color = Color(0xFFE0E0E0),
            radius = dohyoOuterRadius,
            center = center
        )

        // 3. Black combat surface (72 cm diameter interior)
        drawCircle(
            color = Color(0xFF181818),
            radius = blackFieldRadius,
            center = center
        )

        // Outer white border trim line
        drawCircle(
            color = Color.White,
            radius = dohyoOuterRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Shikiri-sen starting lines in center (standard Mini Sumo lines: 1x10 cm, 10 cm apart)
        val startLineHalfLen = 5.0f * pxPerCm
        val startLineOffset = 5.0f * pxPerCm
        val startLineWidth = 1.2f * pxPerCm

        // Left starting line
        drawLine(
            color = Color(0xFF755B49),
            start = Offset(center.x - startLineOffset, center.y - startLineHalfLen),
            end = Offset(center.x - startLineOffset, center.y + startLineHalfLen),
            strokeWidth = startLineWidth
        )
        // Right starting line
        drawLine(
            color = Color(0xFF755B49),
            start = Offset(center.x + startLineOffset, center.y - startLineHalfLen),
            end = Offset(center.x + startLineOffset, center.y + startLineHalfLen),
            strokeWidth = startLineWidth
        )

        // 4. Robot model in center (Mini sumo is 10 cm x 10 cm)
        val robotHalfSize = 5.0f * pxPerCm
        val robotLeft = center.x - robotHalfSize
        val robotTop = center.y - robotHalfSize
        val robotSize = robotHalfSize * 2f

        // Draw Sensor Projection Cones (Facing forward / UP)
        drawDistanceSensorCone(
            center = center,
            angleDeg = -90f - 18f, // Left cone (~18 degrees from forward)
            distanceCm = telemetry.distL,
            pxPerCm = pxPerCm
        )
        drawDistanceSensorCone(
            center = center,
            angleDeg = -90f + 18f, // Right cone (~18 degrees from forward)
            distanceCm = telemetry.distR,
            pxPerCm = pxPerCm
        )

        // Robot Chassis body
        drawRoundRect(
            color = Color(0xFF263238),
            topLeft = Offset(robotLeft, robotTop),
            size = Size(robotSize, robotSize),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )

        // Robot Scoop / Blade (Front facing UP)
        drawRect(
            color = Color(0xFFB0BEC5),
            topLeft = Offset(robotLeft, robotTop),
            size = Size(robotSize, 3.dp.toPx())
        )

        // Robot Direction Arrow
        val arrowPath = Path().apply {
            moveTo(center.x, robotTop + 4.dp.toPx())
            lineTo(center.x - 5.dp.toPx(), robotTop + 12.dp.toPx())
            lineTo(center.x + 5.dp.toPx(), robotTop + 12.dp.toPx())
            close()
        }
        drawPath(arrowPath, color = Color(0xFF4CAF50))

        // 5. Line Sensor Collision Zones
        // Front-left edge sensor indicator
        val leftLineColor = if (telemetry.lineL) Color(0xFFFF1744) else Color(0xFF37474F)
        drawCircle(
            color = leftLineColor,
            radius = 5.dp.toPx(),
            center = Offset(robotLeft + 4.dp.toPx(), robotTop + 4.dp.toPx())
        )

        // Front-right edge sensor indicator
        val rightLineColor = if (telemetry.lineR) Color(0xFFFF1744) else Color(0xFF37474F)
        drawCircle(
            color = rightLineColor,
            radius = 5.dp.toPx(),
            center = Offset(robotLeft + robotSize - 4.dp.toPx(), robotTop + 4.dp.toPx())
        )
    }
}

private fun DrawScope.drawDistanceSensorCone(
    center: Offset,
    angleDeg: Float,
    distanceCm: Int,
    pxPerCm: Float
) {
    if (distanceCm <= 0) return

    val clampedDistCm = distanceCm.coerceIn(4, 65).toFloat()
    val coneLengthPx = clampedDistCm * pxPerCm
    val halfSpreadDeg = 12f // 24-degree sensor FOV cone

    val angleRad = Math.toRadians(angleDeg.toDouble())
    val leftAngleRad = Math.toRadians((angleDeg - halfSpreadDeg).toDouble())
    val rightAngleRad = Math.toRadians((angleDeg + halfSpreadDeg).toDouble())

    val conePath = Path().apply {
        moveTo(center.x, center.y)
        lineTo(
            (center.x + coneLengthPx * cos(leftAngleRad)).toFloat(),
            (center.y + coneLengthPx * sin(leftAngleRad)).toFloat()
        )
        lineTo(
            (center.x + coneLengthPx * cos(rightAngleRad)).toFloat(),
            (center.y + coneLengthPx * sin(rightAngleRad)).toFloat()
        )
        close()
    }

    val isAlert = distanceCm in 1..20
    val coneColor = if (isAlert) Color(0xFFFF3D00).copy(alpha = 0.45f) else Color(0xFF00E5FF).copy(alpha = 0.25f)
    val pingDotColor = if (isAlert) Color(0xFFFF3D00) else Color(0xFF00E5FF)

    drawPath(conePath, color = coneColor)

    // Obstacle detected ping point
    val pingX = (center.x + coneLengthPx * cos(angleRad)).toFloat()
    val pingY = (center.y + coneLengthPx * sin(angleRad)).toFloat()
    drawCircle(
        color = pingDotColor,
        radius = 4.dp.toPx(),
        center = Offset(pingX, pingY)
    )
}
