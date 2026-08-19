package com.farmmathbuilder.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiPiece(val angle: Float, val speed: Float, val color: Color, val sizePx: Float)

/**
 * Simple particle/confetti-style Compose animation played on harvest (FR-015/FR-066).
 * Purely programmatic — no image assets required.
 */
@Composable
fun ConfettiOverlay(onFinished: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val colors = listOf(
        Color(0xFFFFC107), Color(0xFF8BC34A), Color(0xFF29B6F6), Color(0xFFFF7043), Color(0xFFAB47BC)
    )
    val pieces = remember {
        List(24) {
            ConfettiPiece(
                angle = Random.nextFloat() * 360f,
                speed = 60f + Random.nextFloat() * 90f,
                color = colors[Random.nextInt(colors.size)],
                sizePx = 6f + Random.nextFloat() * 6f
            )
        }
    }
    var progress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "confetti"
    )

    LaunchedEffect(Unit) {
        progress = 1f
        delay(950)
        onFinished()
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (p in pieces) {
            val rad = Math.toRadians(p.angle.toDouble())
            val dist = p.speed * animatedProgress
            val x = cx + (cos(rad) * dist).toFloat()
            val y = cy + (sin(rad) * dist).toFloat() - (40f * animatedProgress) // slight upward drift
            val alpha = (1f - animatedProgress).coerceIn(0f, 1f)
            drawCircle(
                color = p.color.copy(alpha = alpha),
                radius = p.sizePx,
                center = Offset(x, y)
            )
        }
    }
}
