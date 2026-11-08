package com.example.sumoroboto.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// ---------------- Paraméterek (cm, másodperc) ----------------
private const val OUTER_R = 38.5f       // ring sugara
private const val BLACK_R = 36f         // fekete felület sugara
private const val ROBOT_R = 6f          // ütközési sugár
private const val ACCEL = 130f
private const val FRICTION = 2.6f
private const val GRIP = 8f             // oldalirányú tapadás
private const val PLAYER_TURN = 4.2f    // rad/s
private const val PUSH = 220f
private const val RESTITUTION = 0.2f
private const val ROUND_MAX = 45f
private const val WIN_SCORE = 2
private const val JOY_RADIUS_DP = 60
private const val PI_F = PI.toFloat()

enum class GameDifficulty(
    val label: String,
    val thr: Float,
    val turn: Float,
    val edge: Float,
    val noise: Float
) {
    EASY("Könnyű", 0.55f, 2.4f, 0.80f, 0.9f),
    NORMAL("Normál", 0.80f, 3.4f, 0.85f, 0.4f),
    HARD("Nehéz", 1.00f, 4.6f, 0.88f, 0.1f)
}

private enum class GamePhase { IDLE, COUNTDOWN, PLAY, ROUND_END, MATCH_END }

private class Bot(var x: Float, var y: Float, var a: Float, var turnRate: Float) {
    var vx = 0f
    var vy = 0f
    var thr = 0f
    var turn = 0f
    var out = false
}

// ---------------- Játékmotor (nem függ a UI-tól) ----------------
private class MiniGameEngine {
    var difficulty = GameDifficulty.NORMAL
    var phase = GamePhase.IDLE
    var phaseTimer = 0f
    var roundTime = 0f
    var round = 1
    var scoreP = 0
    var scoreA = 0
    var message = ""
    var clock = 0f
    var joyX = 0f
    var joyY = 0f
    var player = Bot(0f, 9f, -PI_F / 2f, PLAYER_TURN)
    var ai = Bot(0f, -9f, PI_F / 2f, GameDifficulty.NORMAL.turn)

    fun resetRound() {
        player = Bot(0f, 9f, -PI_F / 2f, PLAYER_TURN)          // te: lent, felfelé néz
        ai = Bot(0f, -9f, PI_F / 2f, difficulty.turn)           // AI: fent, lefelé néz
        roundTime = 0f
        phase = GamePhase.COUNTDOWN
        phaseTimer = 3f
    }

    fun newMatch() {
        scoreP = 0
        scoreA = 0
        round = 1
        resetRound()
    }

    fun idle() {
        newMatch()
        phase = GamePhase.IDLE
    }

    private fun endRound(text: String, winner: Int) {
        if (winner > 0) scoreP++
        if (winner < 0) scoreA++
        message = text
        phase = GamePhase.ROUND_END
        phaseTimer = 2f
    }

    fun update(dt: Float) {
        clock += dt
        when (phase) {
            GamePhase.COUNTDOWN -> {
                phaseTimer -= dt
                if (phaseTimer <= 0f) phase = GamePhase.PLAY
            }
            GamePhase.PLAY -> {
                roundTime += dt
                readInput(player)
                aiControl(ai, player)
                step(player, dt)
                step(ai, dt)
                collide(player, ai, dt)
                player.out = hypot(player.x, player.y) > OUTER_R
                ai.out = hypot(ai.x, ai.y) > OUTER_R
                when {
                    player.out && ai.out -> endRound("Döntetlen kör", 0)
                    ai.out -> endRound("Pont neked! 💪", 1)
                    player.out -> endRound("Pont az AI-nak", -1)
                    roundTime >= ROUND_MAX -> {
                        val dp = hypot(player.x, player.y)
                        val da = hypot(ai.x, ai.y)
                        if (dp < da) endRound("Lejárt az idő: pont neked", 1)
                        else endRound("Lejárt az idő: pont az AI-nak", -1)
                    }
                }
            }
            GamePhase.ROUND_END -> {
                player.thr = 0f; player.turn = 0f
                ai.thr = 0f; ai.turn = 0f
                step(player, dt)
                step(ai, dt)
                phaseTimer -= dt
                if (phaseTimer <= 0f) {
                    if (scoreP >= WIN_SCORE || scoreA >= WIN_SCORE) {
                        message = if (scoreP > scoreA) "Győztél! 🏆" else "Vesztettél"
                        phase = GamePhase.MATCH_END
                    } else {
                        round++
                        resetRound()
                    }
                }
            }
            else -> Unit
        }
    }

    private fun dz(v: Float) = if (abs(v) < 0.12f) 0f else v

    private fun readInput(r: Bot) {
        r.thr = -dz(joyY)   // fel = gáz
        r.turn = dz(joyX)   // jobbra = jobbra kanyar
    }

    private fun step(r: Bot, dt: Float) {
        r.a += r.turn * r.turnRate * dt
        val t = if (r.thr < 0f) r.thr * 0.6f else r.thr
        val fx = cos(r.a)
        val fy = sin(r.a)
        r.vx += (fx * t * ACCEL - r.vx * FRICTION) * dt
        r.vy += (fy * t * ACCEL - r.vy * FRICTION) * dt
        // oldalirányú tapadás: a robot nem csúszkál oldalra
        val vs = -r.vx * fy + r.vy * fx
        val g = 1f - exp(-GRIP * dt)
        r.vx -= -fy * vs * g
        r.vy -= fx * vs * g
        r.x += r.vx * dt
        r.y += r.vy * dt
    }

    private fun collide(a: Bot, b: Bot, dt: Float) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val d = hypot(dx, dy).coerceAtLeast(0.001f)
        val min = 2f * ROBOT_R
        if (d >= min) return
        val nx = dx / d
        val ny = dy / d
        val ov = min - d
        a.x -= nx * ov / 2f; a.y -= ny * ov / 2f
        b.x += nx * ov / 2f; b.y += ny * ov / 2f

        val vr = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
        if (vr < 0f) {
            val j = -(1f + RESTITUTION) * vr / 2f
            a.vx -= j * nx; a.vy -= j * ny
            b.vx += j * nx; b.vy += j * ny
        }
        // Tolás: az nyer, aki szemből, gázzal megy az ellenfélnek
        val pa = max(0f, cos(a.a) * nx + sin(a.a) * ny) * max(0f, a.thr)
        val pb = max(0f, -(cos(b.a) * nx + sin(b.a) * ny)) * max(0f, b.thr)
        val net = (pa - pb) * PUSH * dt
        b.vx += nx * net; b.vy += ny * net
        a.vx -= nx * net; a.vy -= ny * net
    }

    private fun angDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI_F) d -= 2f * PI_F
        while (d < -PI_F) d += 2f * PI_F
        return d
    }

    private fun aiControl(me: Bot, foe: Bot) {
        val p = difficulty
        var want = atan2(foe.y - me.y, foe.x - me.x)
        var thr = p.thr
        if (hypot(me.x, me.y) > BLACK_R * p.edge) {      // a szélen: vissza a közép felé
            want = atan2(-me.y, -me.x)
            thr = 0.5f
        } else {
            want += sin(clock * 3f) * p.noise * 0.5f      // kis tévedés
        }
        val d = angDiff(want, me.a)
        me.turn = (d * 2.5f).coerceIn(-1f, 1f)
        me.thr = if (abs(d) < 0.7f) thr else 0.1f
    }
}

// ---------------- Rajzolás ----------------
private fun DrawScope.drawBot(bot: Bot, center: Offset, pxPerCm: Float, body: Color, arrow: Color) {
    val hf = 5f * pxPerCm   // 10x10 cm alváz
    val alpha = if (bot.out) 0.35f else 1f
    val onEdge = hypot(bot.x, bot.y) > BLACK_R - 2f
    withTransform({
        translate(center.x + bot.x * pxPerCm, center.y + bot.y * pxPerCm)
        rotate(Math.toDegrees(bot.a.toDouble()).toFloat(), Offset.Zero)
    }) {
        drawRoundRect(
            color = body,
            topLeft = Offset(-hf, -hf),
            size = Size(2f * hf, 2f * hf),
            cornerRadius = CornerRadius(4.dp.toPx()),
            alpha = alpha
        )
        drawRect(                                   // első penge
            color = Color(0xFFB0BEC5),
            topLeft = Offset(hf - 3.dp.toPx(), -hf),
            size = Size(3.dp.toPx(), 2f * hf),
            alpha = alpha
        )
        val arrowPath = Path().apply {              // irányjelző nyíl
            moveTo(hf - 6.dp.toPx(), 0f)
            lineTo(hf - 16.dp.toPx(), -6.dp.toPx())
            lineTo(hf - 16.dp.toPx(), 6.dp.toPx())
            close()
        }
        drawPath(arrowPath, color = arrow, alpha = alpha)
        val sensorColor = if (onEdge) Color(0xFFFF1744) else Color(0xFF37474F)
        drawCircle(sensorColor, 4.dp.toPx(), Offset(hf - 4.dp.toPx(), -hf + 4.dp.toPx()), alpha = alpha)
        drawCircle(sensorColor, 4.dp.toPx(), Offset(hf - 4.dp.toPx(), hf - 4.dp.toPx()), alpha = alpha)
    }
}

private fun DrawScope.drawArena(engine: MiniGameEngine) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val pxPerCm = size.width.coerceAtMost(size.height) * 0.92f / 77f

    drawCircle(Color(0x33000000), OUTER_R * pxPerCm + 8.dp.toPx(), Offset(center.x, center.y + 4.dp.toPx()))
    drawCircle(Color(0xFFE0E0E0), OUTER_R * pxPerCm, center)
    drawCircle(Color(0xFF181818), BLACK_R * pxPerCm, center)
    drawCircle(Color.White, OUTER_R * pxPerCm, center, style = Stroke(width = 2.dp.toPx()))

    // Shikiri-sen kezdővonalak
    for (o in listOf(-5f, 5f)) {
        drawLine(
            color = Color(0xFF755B49),
            start = Offset(center.x - 5f * pxPerCm, center.y + o * pxPerCm),
            end = Offset(center.x + 5f * pxPerCm, center.y + o * pxPerCm),
            strokeWidth = 1.2f * pxPerCm
        )
    }

    drawBot(engine.ai, center, pxPerCm, Color(0xFF4E2A2A), Color(0xFFFF5252))
    drawBot(engine.player, center, pxPerCm, Color(0xFF263238), Color(0xFF4CAF50))
}

// ---------------- Composable ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SumoMiniGame(modifier: Modifier = Modifier) {
    val engine = remember { MiniGameEngine().also { it.idle() } }
    var tick by remember { mutableStateOf(0L) }
    var difficulty by remember { mutableStateOf(GameDifficulty.NORMAL) }

    // Játékciklus: képkockánként frissít
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                engine.update(dt)
                tick = now
            }
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val frame = tick   // ettől frissül a felület minden képkockán

    val timeLeft = if (engine.phase == GamePhase.PLAY)
        ceil(max(0f, ROUND_MAX - engine.roundTime)).toInt() else ROUND_MAX.toInt()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // HUD
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Forduló ${engine.round}", style = MaterialTheme.typography.labelLarge)
            Text(
                "Te ${engine.scoreP} : ${engine.scoreA} AI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text("$timeLeft s", style = MaterialTheme.typography.labelLarge)
        }

        // Pálya + láthatatlan joystick (húzás)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(1f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (engine.phase == GamePhase.IDLE || engine.phase == GamePhase.MATCH_END) {
                                engine.newMatch()
                            }
                            val origin = down.position
                            val radius = JOY_RADIUS_DP.dp.toPx()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val dx = (change.position.x - origin.x) / radius
                                val dy = (change.position.y - origin.y) / radius
                                val m = hypot(dx, dy)
                                val k = if (m > 1f) 1f / m else 1f
                                engine.joyX = dx * k
                                engine.joyY = dy * k
                                change.consume()
                            }
                            engine.joyX = 0f
                            engine.joyY = 0f
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArena(engine)
                }

                val (title, subtitle) = when (engine.phase) {
                    GamePhase.IDLE -> "Sumo Minijáték" to "Koppints a pályára az indításhoz"
                    GamePhase.COUNTDOWN -> ceil(engine.phaseTimer).toInt().toString() to null
                    GamePhase.ROUND_END -> engine.message to null
                    GamePhase.MATCH_END -> engine.message to "Koppints az új meccshez"
                    GamePhase.PLAY -> "" to null
                }
                if (title.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = if (engine.phase == GamePhase.COUNTDOWN) 64.sp else 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (subtitle != null) {
                            Text(text = subtitle, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Text(
            text = "Húzd az ujjad a pályán: fel = gáz, le = hátra, oldalra = kanyar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Nehézség + új meccs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameDifficulty.values().forEach { d ->
                FilterChip(
                    selected = difficulty == d,
                    onClick = {
                        difficulty = d
                        engine.difficulty = d
                    },
                    label = { Text(d.label, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
            Button(onClick = { engine.newMatch() }) {
                Text("Új meccs", fontSize = 12.sp)
            }
        }
    }
}