package com.farmmathbuilder.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.domain.SlotAvailability
import com.farmmathbuilder.app.ui.components.CellActionDialog
import com.farmmathbuilder.app.ui.components.ConfettiOverlay
import com.farmmathbuilder.app.ui.components.ExpandMapButton
import com.farmmathbuilder.app.ui.components.FabColumn
import com.farmmathbuilder.app.ui.components.FarmGridCanvas
import com.farmmathbuilder.app.ui.components.MathExerciseDialog
import com.farmmathbuilder.app.ui.components.StatsDialog
import com.farmmathbuilder.app.ui.components.TopHud
import com.farmmathbuilder.app.viewmodel.FarmViewModel

@Composable
fun FarmScreen(
    viewModel: FarmViewModel,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showStats by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.dailyResetSnackbar) {
        if (uiState.dailyResetSnackbar) {
            snackbarHostState.showSnackbar("5 new fields available!")
            viewModel.consumeSnackbars()
        }
    }
    LaunchedEffect(uiState.noSlotsSnackbar) {
        if (uiState.noSlotsSnackbar) {
            snackbarHostState.showSnackbar("No fields left today — solve a math problem for more!")
            viewModel.consumeSnackbars()
        }
    }
    LaunchedEffect(uiState.expandGridSnackbar) {
        uiState.expandGridSnackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeExpandGridSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            FarmGridCanvas(
                cells = uiState.cells,
                gridConfig = uiState.gridConfig,
                highlightedCellId = null,
                onCellTapped = { cellId -> viewModel.onCellTapped(cellId) },
                modifier = Modifier.fillMaxSize()
            )

            TopHud(
                player = uiState.player,
                modifier = Modifier.align(Alignment.TopStart)
            )

            FabColumn(
                onMathClick = { viewModel.openExercise() },
                onStatsClick = { showStats = true },
                onSettingsClick = onOpenSettings,
                modifier = Modifier.align(Alignment.BottomEnd)
            )

            val expansionCost = viewModel.nextExpansionCost()
            ExpandMapButton(
                cost = expansionCost,
                canAfford = (uiState.player?.wheatCurrency ?: 0) >= expansionCost,
                onClick = { viewModel.expandGrid() },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
            )

            uiState.selectedCellId?.let { cellId ->
                val cell = uiState.cells.find { it.id == cellId }
                if (cell != null) {
                    val availability = uiState.player?.let {
                        when {
                            it.freeFieldsUsedToday < 5 -> SlotAvailability.FREE_SLOT_AVAILABLE
                            it.extraFieldsEarnedToday > it.extraFieldsUsedToday -> SlotAvailability.EXTRA_SLOT_AVAILABLE
                            else -> SlotAvailability.NONE_AVAILABLE
                        }
                    } ?: SlotAvailability.NONE_AVAILABLE

                    CellActionDialog(
                        cell = cell,
                        slotAvailability = availability,
                        onDismiss = { viewModel.dismissCellPopup() },
                        onBuildFree = { viewModel.buildFreeOrExtra(cellId) },
                        onSolveExercise = {
                            viewModel.dismissCellPopup()
                            viewModel.openExercise()
                        },
                        onCancelGrowth = { viewModel.cancelGrowth(cellId) },
                        onSolveToSaveTime = { viewModel.openExerciseForTimeReduction(cellId) }
                    )
                }
            }

            uiState.activeExercise?.let { exercise ->
                MathExerciseDialog(
                    exercise = exercise,
                    lastAnswerCorrect = uiState.lastAnswerCorrect,
                    isTimeReduction = uiState.exercisePurposeCellId != null,
                    onAnswer = { viewModel.submitAnswer(it) },
                    onNext = { viewModel.nextExercise() },
                    onClose = { viewModel.closeExercise() }
                )
            }

            uiState.showHarvestCelebrationForCellId?.let {
                ConfettiOverlay(onFinished = { viewModel.clearHarvestCelebration() })
            }

            if (showStats) {
                StatsDialog(player = uiState.player, onDismiss = { showStats = false })
            }
        }
    }
}
