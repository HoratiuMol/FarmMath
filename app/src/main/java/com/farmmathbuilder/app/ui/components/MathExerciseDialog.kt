package com.farmmathbuilder.app.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * FR-060/061/062: full-screen modal overlay over the farm view (does not unmount
 * it — the caller keeps FarmGridCanvas composed underneath). One question, 4
 * multiple-choice buttons, immediate feedback, unlimited retry, no penalty.
 */
@Composable
fun MathExerciseDialog(
    exercise: Exercise,
    lastAnswerCorrect: Boolean?,
    isGuidedTutorial: Boolean,
    isTimeReduction: Boolean = false,
    onAnswer: (Int) -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (isTimeReduction) "Solve to save 1 minute!" else "Solve to unlock a field!",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
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

            when (lastAnswerCorrect) {
                true -> {
                    Text(
                        if (isTimeReduction) "🎉 Correct! -1 minute of growth time." else "🎉 Correct! +1 extra field today.",
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onClose) { Text(if (isGuidedTutorial) "Continue" else "Nice!") }
                }
                false -> {
                    Text("Not quite — try again!", color = Color(0xFFC62828))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onNext) { Text("Try another") }
                }
                null -> {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
