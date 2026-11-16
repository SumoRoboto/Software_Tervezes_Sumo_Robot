package com.example.sumoroboto.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector

enum class ScreenDestination(
    val title: String,
    val icon: ImageVector
) {
    TELEMETRY("Telemetry", Icons.Default.Speed),
    HISTORY("Adatok", Icons.Default.Timeline),
    DOHYO("Arena", Icons.Default.SportsMartialArts),
    SETTINGS("Settings", Icons.Default.Settings)
}
