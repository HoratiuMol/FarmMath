package com.farmmathbuilder.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * The little face a child sees while solving a math problem (founder
 * gameplay push: "math más jugable" — wrap the exercise in something that
 * reacts, the way Prodigy's pet companion does, but without that pattern's
 * guilt/neglect hook). Purely positive: it bounces and sparkles on a correct
 * answer, and only ever wiggles — never looks sad or scared — on a wrong one,
 * matching MathExerciseDialog/ChallengeDialog's existing "no penalty, just
 * try again" tone.
 *
 * `state` mirrors lastAnswerCorrect: null = idle (question just shown),
 * true = correct, false = incorrect.
 */
@Composable
fun MathMascot(state: Boolean?, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(state) {
        when (state) {
            true -> {
                scale.snapTo(0.9f)
                scale.animateTo(1.3f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
            }
            false -> {
                // A friendly head-wiggle, not a flinch — reinforces "no
                // penalty" rather than "you did something wrong".
                for (target in listOf(-10f, 10f, -6f, 6f, 0f)) {
                    rotation.animateTo(target, tween(70))
                }
            }
            null -> {
                scale.snapTo(1f)
                rotation.snapTo(0f)
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            "🐮",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier
                .scale(scale.value)
                .rotate(rotation.value)
        )
        if (state == true) {
            Text(
                "⭐",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-4).dp)
                    .scale(scale.value)
            )
        }
    }
}
