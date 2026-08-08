package com.farmmathbuilder.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.farmmathbuilder.app.viewmodel.TutorialStep

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
            val isTutorialActive = uiState.needsOnboarding &&
                uiState.tutorialStep in listOf(
                    TutorialStep.GUIDED_BUILD, TutorialStep.WAIT_GROWTH,
                    TutorialStep.GUIDED_HARVEST, TutorialStep.GUIDED_EXERCISE
                )
            val highlightCellId = if (isTutorialActive && uiState.tutorialStep == TutorialStep.GUIDED_BUILD) {
                uiState.tutorialFirstFieldCellId
            } else null

            FarmGridCanvas(
                cells = uiState.cells,
                gridConfig = uiState.gridConfig,
                highlightedCellId = highlightCellId,
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

            if (isTutorialActive) {
                TutorialTooltip(
                    step = uiState.tutorialStep,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp)
                )
            }

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
                    isGuidedTutorial = uiState.exerciseIsGuidedTutorial,
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

    // Auto-open the guided exercise once the tutorial reaches that step.
    LaunchedEffect(uiState.tutorialStep) {
        if (uiState.tutorialStep == TutorialStep.GUIDED_EXERCISE && uiState.activeExercise == null) {
            viewModel.openExercise(guidedTutorial = true)
        }
    }
}

@Composable
private fun TutorialTooltip(step: TutorialStep, modifier: Modifier = Modifier) {
    val text = when (step) {
        TutorialStep.GUIDED_BUILD -> "Tap the glowing cell to build your first field!"
        TutorialStep.WAIT_GROWTH -> "Your wheat is growing... it'll be ready soon!"
        TutorialStep.GUIDED_HARVEST -> "Tap to harvest your wheat!"
        TutorialStep.GUIDED_EXERCISE -> "Solve this to unlock another field!"
        else -> ""
    }
    if (text.isNotEmpty()) {
        Box(
            modifier = modifier
                .background(Color(0xEE212121), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
