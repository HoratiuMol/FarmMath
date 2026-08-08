package com.farmmathbuilder.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.domain.GrowthCalculator
import com.farmmathbuilder.app.domain.SlotAvailability
import com.farmmathbuilder.app.domain.UiCell

/**
 * Popup shown when tapping an empty (buildable) cell or a growing crop.
 * Implements the decision table from BRD Section 6. For a growing cell it shows
 * a live countdown (recomputed every tick since `cell` is a fresh UiCell each
 * time the ViewModel's 1s ticker fires) plus cancel / "solve to save 1 minute".
 */
@Composable
fun CellActionDialog(
    cell: UiCell,
    slotAvailability: SlotAvailability,
    onDismiss: () -> Unit,
    onBuildFree: () -> Unit,
    onSolveExercise: () -> Unit,
    onCancelGrowth: () -> Unit,
    onSolveToSaveTime: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (cell.isEmpty) "Build a wheat field?" else "Growing wheat") },
        text = {
            Column {
                when {
                    cell.isEmpty && slotAvailability != SlotAvailability.NONE_AVAILABLE -> {
                        Text("Plant a wheat seed here. It will take about 10 minutes to grow.")
                    }
                    cell.isEmpty -> {
                        Text("You've used all your free fields today. Solve a math problem to unlock one more!")
                    }
                    cell.isGrowing -> {
                        val remainingMs = GrowthCalculator.remainingMillis(cell.plantedAtTimestamp, cell.growthDurationMs)
                        val totalSeconds = remainingMs / 1000L
                        val minutes = totalSeconds / 60
                        val seconds = totalSeconds % 60
                        Text("Ready in %d:%02d".format(minutes, seconds))
                        Spacer(Modifier.height(4.dp))
                        Text("Cancel it to get your slot back, or solve a problem to save 1 minute.")
                    }
                    else -> {}
                }
                Spacer(Modifier.height(8.dp))
            }
        },
        // All actions (including "Close") live in a single, deliberately
        // stacked Column inside confirmButton, with no separate dismissButton
        // slot — Material3 AlertDialog lays confirmButton/dismissButton out
        // side-by-side in a row, which visually overlapped once the growing-
        // cell case needed two stacked buttons in one slot (that slot's
        // reported width, driven by the long "Solve a problem..." label,
        // broke the row's side-by-side assumption). One column we fully
        // control avoids that entirely.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                when {
                    cell.isEmpty && slotAvailability != SlotAvailability.NONE_AVAILABLE -> {
                        Button(onClick = onBuildFree) { Text("Build") }
                    }
                    cell.isEmpty -> {
                        Button(onClick = onSolveExercise) { Text("Solve exercise") }
                    }
                    cell.isGrowing -> {
                        Button(onClick = onSolveToSaveTime) { Text("Solve a problem to save 1 minute") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onCancelGrowth) { Text("Cancel growth") }
                    }
                    else -> {}
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
