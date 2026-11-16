package com.example.sumoroboto.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sumoroboto.data.model.BatteryState
import java.util.Locale

@Composable
fun BatteryWidget(
    batteryState: BatteryState,
    modifier: Modifier = Modifier
) {
    val isCritical = batteryState.isCritical
    val percent = batteryState.percentage
    val voltage = batteryState.voltage

    val barColor by animateColorAsState(
        targetValue = when {
            isCritical -> Color(0xFFE53935) // Critical Red
            percent < 30 -> Color(0xFFFFA000) // Amber
            percent < 60 -> Color(0xFFFFD600) // Yellow
            else -> Color(0xFF43A047) // Green
        },
        label = "batteryColor"
    )

    val barProgress by animateFloatAsState(
        targetValue = (percent / 100f).coerceIn(0.05f, 1f),
        label = "batteryProgress"
    )

    val containerBg by animateColorAsState(
        targetValue = if (isCritical) Color(0xFF3E1A1A) else MaterialTheme.colorScheme.surfaceVariant,
        label = "containerBg"
    )

    val containerBorder by animateColorAsState(
        targetValue = if (isCritical) Color(0xFFE53935).copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
        label = "containerBorder"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerBg)
            .border(1.dp, containerBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnimatedVisibility(
            visible = isCritical,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Battery Low Warning",
                tint = Color(0xFFE53935),
                modifier = Modifier.size(16.dp)
            )
        }

        // Mini battery physical icon outline
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(22.dp)
                    .height(11.dp)
                    .border(1.dp, if (isCritical) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))
                    .padding(1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(barProgress)
                        .clip(RoundedCornerShape(1.dp))
                        .background(barColor)
                )
            }
            // Battery positive terminal nub
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp))
                    .background(if (isCritical) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(min = 38.dp)
        ) {
            Text(
                text = "$percent%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCritical) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = String.format(Locale.US, "%.2fV", voltage),
                fontSize = 9.sp,
                color = if (isCritical) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
