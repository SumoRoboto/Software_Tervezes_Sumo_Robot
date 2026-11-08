package com.example.sumoroboto.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sumoroboto.R
import kotlin.math.roundToInt

// Exact background color matching the Sumo Robot logo
private val LOGO_BACKGROUND_COLOR = Color(0xFF2D3B4B)

@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 2000,
                easing = FastOutSlowInEasing
            )
        )
        onFinished()
    }

    val currentPercent = (progress.value * 100).roundToInt()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LOGO_BACKGROUND_COLOR)
    ) {
        // Centered App Logo and Title
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo Container with matching background & subtle outline
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .shadow(16.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(LOGO_BACKGROUND_COLOR)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_sumo_logo),
                    contentDescription = "Sumo Robot Logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "MINI SUMO ROBOT",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "TELEMETRY & LOGGING",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Bottom Loading Bar & Skip Action
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Loading system...",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = "$currentPercent%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onFinished,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.6f)
                )
            ) {
                Text("Skip", fontSize = 13.sp)
                Spacer(modifier = Modifier.size(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
