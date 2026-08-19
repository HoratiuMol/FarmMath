package com.farmmathbuilder.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.farmmathbuilder.app.domain.Exercise
import com.farmmathbuilder.app.domain.MathOperation

/**
 * Founder-requested "10 exercises in a row" Challenge — a dedicated feature
 * with its own entry point (the trophy FAB above the calculator FAB), kept
 * deliberately separate from the casual single-exercise-for-a-field flow
 * (MathExerciseDialog): a wrong answer here ends the whole attempt with no
 * reward (must restart via the FAB), and correct answers do NOT grant the
 * usual +1 field individually — completing all [challengeLength] in a row is
 * the only way to earn this feature's reward, a single random bonus **pack**
 * of fields (see FarmRepository.grantChallengeBonus), landing all at once
 * instead of one at a time.
 */
@Composable
fun ChallengeDialog(
    exercise: Exercise,
    correctCount: Int,
    challengeLength: Int,
    lastAnswerCorrect: Boolean?,
    completedBonusFields: Int?,
    failed: Boolean,
    onAnswer: (Int) -> Unit,
    onNextQuestion: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "🔥 Challenge: $correctCount / $challengeLength in a row",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Solve $challengeLength in a row to unlock a bonus pack of fields!",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A6F5C)
                )
                Spacer(Modifier.height(8.dp))
                MathMascot(state = lastAnswerCorrect, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(8.dp))

                // The question/choices only make sense mid-attempt — once the
                // challenge has ended (completed or failed) they're replaced
                // entirely by the outcome message below.
                if (completedBonusFields == null && !failed) {
                    val symbol = when (exercise.operation) {
                        MathOperation.ADD -> "+"
                        MathOperation.SUBTRACT -> "-"
                        MathOperation.MULTIPLY -> "×"
                        MathOperation.DIVIDE -> "÷"
                    }
                    Text(
                        "${exercise.operandA} $symbol ${exercise.operandB} = ?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))

                    val rows = exercise.choices.chunked(2)
                    for (row in rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (choice in row) {
                                Button(
                                    onClick = { onAnswer(choice) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(2f),
                                    enabled = lastAnswerCorrect != true
                                ) {
                                    Text("$choice", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                when {
                    completedBonusFields != null -> {
                        Text("🎉 Challenge complete!", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "+$completedBonusFields bonus fields, plus bonus ⭐ stars!",
                            color = Color(0xFFEF6C00),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onClose) { Text("Awesome!") }
                    }
                    failed -> {
                        Text("Not quite — the challenge resets.", color = Color(0xFFC62828))
                        Spacer(Modifier.height(4.dp))
                        Text("You got $correctCount correct before missing one.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onClose) { Text("Close") }
                    }
                    lastAnswerCorrect == true -> {
                        Text("🎉 Correct! ($correctCount/$challengeLength) +1 ⭐", color = Color(0xFF2E7D32))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNextQuestion) { Text("Next question") }
                    }
                    else -> {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onClose) { Text("Give up") }
                    }
                }
            }

            if (lastAnswerCorrect == true) {
                ConfettiOverlay(onFinished = {}, modifier = Modifier.matchParentSize())
            }
        }
    }
}
