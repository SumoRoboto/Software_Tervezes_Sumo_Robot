package com.example.sumoroboto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sumoroboto.data.model.TelemetryPointDto
import kotlin.math.roundToInt

enum class GraphMetric(val label: String) {
    BATTERY("Akkufeszültség"),
    DISTANCE("Távolság (cm)"),
    PWM("Motor PWM")
}

@Composable
fun TelemetryLineChart(
    points: List<TelemetryPointDto>,
    metric: GraphMetric,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    var touchedIndex by remember(points) { mutableStateOf<Int?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: Title and Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title ?: metric.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Legend items
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (metric) {
                        GraphMetric.BATTERY -> {
                            LegendItem(color = Color(0xFF00E5FF), label = "VBat (V)")
                            LegendItem(color = Color(0xFFEF5350), label = "6.4V Limit", isDashed = true)
                        }
                        GraphMetric.DISTANCE -> {
                            LegendItem(color = Color(0xFF29B6F6), label = "Bal (s1)")
                            LegendItem(color = Color(0xFFFFB300), label = "Jobb (s2)")
                            LegendItem(color = Color(0xFFEF5350), label = "5cm Veszély", isDashed = true)
                        }
                        GraphMetric.PWM -> {
                            LegendItem(color = Color(0xFFAB47BC), label = "Motor L")
                            LegendItem(color = Color(0xFFFF7043), label = "Motor R")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Value inspector banner when touched
            val activeIndex = touchedIndex
            if (activeIndex != null && activeIndex in points.indices) {
                val pt = points[activeIndex]
                val timeSec = String.format("%.2f", pt.relTimeMs / 1000f)
                val detailText = when (metric) {
                    GraphMetric.BATTERY -> "${pt.vbat} V (Akkumulátor)"
                    GraphMetric.DISTANCE -> "Bal: ${pt.distL} cm | Jobb: ${pt.distR} cm"
                    GraphMetric.PWM -> "Bal: ${pt.pwmL} | Jobb: ${pt.pwmR}"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "T+${timeSec}s (#${activeIndex + 1})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = detailText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Main Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                if (points.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShowChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Nincs megjeleníthető adatpont",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Indíts in-app szimulációt vagy tölts be egy mentett futamot",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(points) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        touchedIndex = calculateNearestPointIndex(offset.x, size.width.toFloat(), points.size)
                                    }
                                )
                            }
                            .pointerInput(points) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        touchedIndex = calculateNearestPointIndex(offset.x, size.width.toFloat(), points.size)
                                    },
                                    onDrag = { change, _ ->
                                        touchedIndex = calculateNearestPointIndex(change.position.x, size.width.toFloat(), points.size)
                                    },
                                    onDragEnd = { /* keep last selected */ }
                                )
                            }
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height

                        val paddingLeft = 36.dp.toPx()
                        val paddingRight = 12.dp.toPx()
                        val paddingTop = 12.dp.toPx()
                        val paddingBottom = 22.dp.toPx()

                        val plotWidth = (canvasWidth - paddingLeft - paddingRight).coerceAtLeast(1f)
                        val plotHeight = (canvasHeight - paddingTop - paddingBottom).coerceAtLeast(1f)

                        val (minY, maxY) = when (metric) {
                            GraphMetric.BATTERY -> Pair(5.8f, 8.6f)
                            GraphMetric.DISTANCE -> Pair(0f, 85f)
                            GraphMetric.PWM -> Pair(-20f, 260f)
                        }

                        // 1. Draw horizontal grid lines and labels
                        val gridSteps = 4
                        for (i in 0..gridSteps) {
                            val ratio = i.toFloat() / gridSteps
                            val y = paddingTop + plotHeight * (1f - ratio)
                            val value = minY + ratio * (maxY - minY)

                            // Grid line
                            drawLine(
                                color = Color.White.copy(alpha = 0.08f),
                                start = Offset(paddingLeft, y),
                                end = Offset(canvasWidth - paddingRight, y),
                                strokeWidth = 1.dp.toPx()
                            )

                            // Label text
                            val label = when (metric) {
                                GraphMetric.BATTERY -> String.format("%.1fV", value)
                                GraphMetric.DISTANCE -> "${value.roundToInt()}"
                                GraphMetric.PWM -> "${value.roundToInt()}"
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                6.dp.toPx(),
                                y + 4.dp.toPx(),
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 24f
                                    isAntiAlias = true
                                }
                            )
                        }

                        // 2. Draw critical threshold line (Rule Engine indicator)
                        when (metric) {
                            GraphMetric.BATTERY -> {
                                val criticalY = paddingTop + plotHeight * (1f - (6.4f - minY) / (maxY - minY))
                                drawDashedLine(
                                    color = Color(0xFFEF5350),
                                    start = Offset(paddingLeft, criticalY),
                                    end = Offset(canvasWidth - paddingRight, criticalY),
                                    strokeWidth = 1.5.dp.toPx()
                                )
                            }
                            GraphMetric.DISTANCE -> {
                                val criticalY = paddingTop + plotHeight * (1f - (5.0f - minY) / (maxY - minY))
                                drawDashedLine(
                                    color = Color(0xFFEF5350),
                                    start = Offset(paddingLeft, criticalY),
                                    end = Offset(canvasWidth - paddingRight, criticalY),
                                    strokeWidth = 1.5.dp.toPx()
                                )
                            }
                            GraphMetric.PWM -> { /* No threshold line */ }
                        }

                        // 3. Coordinate mapping helper
                        fun getPointOffset(index: Int, rawValue: Float): Offset {
                            val xRatio = if (points.size > 1) index.toFloat() / (points.size - 1) else 0.5f
                            val x = paddingLeft + xRatio * plotWidth
                            val yRatio = ((rawValue - minY) / (maxY - minY)).coerceIn(0f, 1f)
                            val y = paddingTop + plotHeight * (1f - yRatio)
                            return Offset(x, y)
                        }

                        // 4. Draw curves
                        when (metric) {
                            GraphMetric.BATTERY -> {
                                val primaryColor = Color(0xFF00E5FF)
                                drawLineCurve(
                                    points = points,
                                    extractValue = { it.vbat },
                                    color = primaryColor,
                                    getOffset = ::getPointOffset,
                                    withFill = true,
                                    plotBottomY = paddingTop + plotHeight
                                )
                            }
                            GraphMetric.DISTANCE -> {
                                drawLineCurve(
                                    points = points,
                                    extractValue = { it.distL.toFloat() },
                                    color = Color(0xFF29B6F6),
                                    getOffset = ::getPointOffset
                                )
                                drawLineCurve(
                                    points = points,
                                    extractValue = { it.distR.toFloat() },
                                    color = Color(0xFFFFB300),
                                    getOffset = ::getPointOffset
                                )
                            }
                            GraphMetric.PWM -> {
                                drawLineCurve(
                                    points = points,
                                    extractValue = { it.pwmL.toFloat() },
                                    color = Color(0xFFAB47BC),
                                    getOffset = ::getPointOffset
                                )
                                drawLineCurve(
                                    points = points,
                                    extractValue = { it.pwmR.toFloat() },
                                    color = Color(0xFFFF7043),
                                    getOffset = ::getPointOffset
                                )
                            }
                        }

                        // 5. Draw Touch Indicator Cursor
                        touchedIndex?.let { idx ->
                            if (idx in points.indices) {
                                val xRatio = if (points.size > 1) idx.toFloat() / (points.size - 1) else 0.5f
                                val touchX = paddingLeft + xRatio * plotWidth
                                drawLine(
                                    color = Color.White.copy(alpha = 0.6f),
                                    start = Offset(touchX, paddingTop),
                                    end = Offset(touchX, paddingTop + plotHeight),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                )

                                // Highlight circle(s)
                                val pt = points[idx]
                                when (metric) {
                                    GraphMetric.BATTERY -> {
                                        val offset = getPointOffset(idx, pt.vbat)
                                        drawCircle(Color.White, radius = 5.dp.toPx(), center = offset)
                                        drawCircle(Color(0xFF00E5FF), radius = 3.dp.toPx(), center = offset)
                                    }
                                    GraphMetric.DISTANCE -> {
                                        val offL = getPointOffset(idx, pt.distL.toFloat())
                                        val offR = getPointOffset(idx, pt.distR.toFloat())
                                        drawCircle(Color(0xFF29B6F6), radius = 4.dp.toPx(), center = offL)
                                        drawCircle(Color(0xFFFFB300), radius = 4.dp.toPx(), center = offR)
                                    }
                                    GraphMetric.PWM -> {
                                        val offL = getPointOffset(idx, pt.pwmL.toFloat())
                                        val offR = getPointOffset(idx, pt.pwmR.toFloat())
                                        drawCircle(Color(0xFFAB47BC), radius = 4.dp.toPx(), center = offL)
                                        drawCircle(Color(0xFFFF7043), radius = 4.dp.toPx(), center = offR)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawDashedLine(
    color: Color,
    start: Offset,
    end: Offset,
    strokeWidth: Float
) {
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
    )
}

private fun DrawScope.drawLineCurve(
    points: List<TelemetryPointDto>,
    extractValue: (TelemetryPointDto) -> Float,
    color: Color,
    getOffset: (Int, Float) -> Offset,
    withFill: Boolean = false,
    plotBottomY: Float = 0f
) {
    if (points.isEmpty()) return

    val strokePath = Path()
    val fillPath = if (withFill) Path() else null

    val firstOffset = getOffset(0, extractValue(points[0]))
    strokePath.moveTo(firstOffset.x, firstOffset.y)
    fillPath?.moveTo(firstOffset.x, plotBottomY)
    fillPath?.lineTo(firstOffset.x, firstOffset.y)

    for (i in 1 until points.size) {
        val currOffset = getOffset(i, extractValue(points[i]))
        strokePath.lineTo(currOffset.x, currOffset.y)
        fillPath?.lineTo(currOffset.x, currOffset.y)
    }

    if (withFill && fillPath != null) {
        val lastOffset = getOffset(points.size - 1, extractValue(points.last()))
        fillPath.lineTo(lastOffset.x, plotBottomY)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.02f)),
                startY = 0f,
                endY = plotBottomY
            )
        )
    }

    drawPath(
        path = strokePath,
        color = color,
        style = Stroke(width = 2.5.dp.toPx())
    )
}

private fun calculateNearestPointIndex(touchX: Float, totalWidth: Float, count: Int): Int {
    if (count <= 1) return 0
    val ratio = (touchX / totalWidth).coerceIn(0f, 1f)
    return (ratio * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    isDashed: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isDashed) {
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(2.dp)
                    .background(color)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
