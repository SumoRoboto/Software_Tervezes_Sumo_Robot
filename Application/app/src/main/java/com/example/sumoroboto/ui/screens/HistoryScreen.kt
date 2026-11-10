package com.example.sumoroboto.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sumoroboto.data.model.HistoryItemDto
import com.example.sumoroboto.data.model.SessionSummaryDto
import com.example.sumoroboto.ui.HistoryFilterType
import com.example.sumoroboto.ui.HistoryUiState
import com.example.sumoroboto.ui.components.GraphMetric
import com.example.sumoroboto.ui.components.TelemetryLineChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onFilterChange: (HistoryFilterType) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onMetricChange: (GraphMetric) -> Unit,
    onSelectSession: (Int) -> Unit,
    onClearSelectedSession: () -> Unit,
    onToggleInAppGenerator: () -> Unit,
    onToggleServerSimulator: () -> Unit,
    onRefresh: () -> Unit,
    onClearDatabase: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Search Bar
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Keresés szabály, állapot vagy időpont alapján...", fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // 2. Filter Category Chips & Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                FilterChip(
                    selected = uiState.filterType == HistoryFilterType.ALL,
                    onClick = { onFilterChange(HistoryFilterType.ALL) },
                    label = { Text("Összes", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = uiState.filterType == HistoryFilterType.COLD,
                    onClick = { onFilterChange(HistoryFilterType.COLD) },
                    label = { Text("Cold Path", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = uiState.filterType == HistoryFilterType.HOT,
                    onClick = { onFilterChange(HistoryFilterType.HOT) },
                    label = { Text("Hot Path", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = uiState.filterType == HistoryFilterType.SESSIONS,
                    onClick = { onFilterChange(HistoryFilterType.SESSIONS) },
                    label = { Text("Munkamenetek", fontSize = 11.sp) }
                )
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(36.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Frissítés",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 3. Quick Actions Toolbar (Simulator controls & DB Clear)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // In-app generator button
            Button(
                onClick = onToggleInAppGenerator,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isAppGenerating) Color(0xFFE91E63) else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isAppGenerating) Icons.Default.Stop else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (uiState.isAppGenerating) "Generátor STOP" else "App Generátor",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Server simulator start/stop
            OutlinedButton(
                onClick = onToggleServerSimulator,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isServerSimulating) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (uiState.isServerSimulating) "Szerver STOP" else "Szerver START",
                    fontSize = 11.sp
                )
            }

            // Database clear button
            IconButton(
                onClick = onClearDatabase,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Adatbázis törlése",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 4. Status / Feedback banner
        AnimatedVisibility(visible = uiState.statusMessage != null) {
            uiState.statusMessage?.let { msg ->
                val bg = if (uiState.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                val fg = if (uiState.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.isError) Icons.Default.ErrorOutline else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = msg, color = fg, fontSize = 11.sp, maxLines = 2)
                }
            }
        }

        // 5. Chart Metric Switcher & Active Target
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selected session indicator
            if (uiState.selectedSessionId != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Session #${uiState.selectedSessionId}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Vissza élőre",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onClearSelectedSession() },
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isAppGenerating) Color(0xFF00E676) else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (uiState.isAppGenerating) "Élő Generálás" else "Élő Puffer",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Metric Switcher Chips
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GraphMetric.values().forEach { metric ->
                    FilterChip(
                        selected = uiState.activeMetric == metric,
                        onClick = { onMetricChange(metric) },
                        label = {
                            Text(
                                text = when (metric) {
                                    GraphMetric.BATTERY -> "Akku"
                                    GraphMetric.DISTANCE -> "Távolság"
                                    GraphMetric.PWM -> "PWM"
                                },
                                fontSize = 10.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // 6. Visualizer: Pure Jetpack Compose Canvas Graph
        val displayPoints = if (uiState.selectedSessionId != null) {
            uiState.selectedSessionPoints
        } else {
            uiState.livePoints
        }

        TelemetryLineChart(
            points = displayPoints,
            metric = uiState.activeMetric,
            title = if (uiState.selectedSessionId != null) "Session #${uiState.selectedSessionId} Görbe" else "Élő Adatfolyam Görbe"
        )

        // 7. Queried Results List Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lekérdezett Eredmények",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            val countText = if (uiState.filterType == HistoryFilterType.SESSIONS) {
                "${uiState.sessions.size} munkamenet"
            } else {
                "${uiState.historyItems.size} bejegyzés"
            }
            Text(text = countText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 8. Result Cards
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.filterType == HistoryFilterType.SESSIONS) {
                if (uiState.sessions.isEmpty()) {
                    item {
                        EmptyListPlaceholder("Nincs mentett munkamenet az adatbázisban.")
                    }
                } else {
                    items(uiState.sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            isSelected = uiState.selectedSessionId == session.id,
                            onSelect = { onSelectSession(session.id) }
                        )
                    }
                }
            } else {
                if (uiState.historyItems.isEmpty()) {
                    item {
                        EmptyListPlaceholder("Nincs találat a szűrésnek megfelelően.")
                    }
                } else {
                    items(uiState.historyItems) { item ->
                        HistoryRecordCard(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionSummaryDto,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (session.sessionType == "MATCH") Color(0xFFFFB300) else Color(0xFF00E5FF),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = session.sessionType,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Session #${session.id}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                val durationSec = String.format("%.1f s", session.durationMs / 1000f)
                Text(
                    text = "$durationSec (${session.pointCount} pont)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Idő: ${session.createdAt}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${session.startVbat}V ➔ ${session.endVbat}V",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (session.notes.isNotBlank()) {
                Text(
                    text = "Megjegyzés: ${session.notes}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (isSelected) "Görbe megjelenítve ✓" else "Megjelenítés grafikonon ➔",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(item: HistoryItemDto) {
    val isHot = item.type.equals("HOT", ignoreCase = true) || item.ruleBroken != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            1.dp,
            if (isHot) Color(0xFFEF5350).copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isHot) Color(0xFFEF5350).copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHot) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.ruleBroken ?: "HOT PATH ALERT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF5350)
                        )
                    } else {
                        Text(
                            text = "COLD PATH (OK)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }

                item.message?.let {
                    Text(text = it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                // Measurement summary
                val dataMap = item.data
                if (dataMap != null) {
                    val bat = dataMap["bat"]
                    val s1 = dataMap["s1"] ?: dataMap["dist_l"]
                    val s2 = dataMap["s2"] ?: dataMap["dist_r"]
                    val pwmA = dataMap["pwmA"] ?: dataMap["pwm_l"]
                    val pwmB = dataMap["pwmB"] ?: dataMap["pwm_r"]
                    val state = dataMap["state"]

                    val summary = buildString {
                        if (bat != null) append("Bat: ${bat}V ")
                        if (s1 != null && s2 != null) append("| S1: ${s1}cm S2: ${s2}cm ")
                        if (pwmA != null && pwmB != null) append("| PWM: $pwmA/$pwmB ")
                        if (state != null) append("| $state")
                    }
                    if (summary.isNotBlank()) {
                        Text(text = summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text(
                text = item.timestamp.takeLast(8),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyListPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
