package com.farmmathbuilder.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.farmmathbuilder.app.audio.SoundManager
import com.farmmathbuilder.app.data.repository.FarmRepository
import com.farmmathbuilder.app.domain.SlotAvailability
import com.farmmathbuilder.app.ui.components.AnimalShopColumn
import com.farmmathbuilder.app.ui.components.CellActionDialog
import com.farmmathbuilder.app.ui.components.ChallengeDialog
import com.farmmathbuilder.app.ui.components.ConfettiOverlay
import com.farmmathbuilder.app.ui.components.DecorationPickerDialog
import com.farmmathbuilder.app.ui.components.DecorationPlacementBanner
import com.farmmathbuilder.app.ui.components.ExpandMapButton
import com.farmmathbuilder.app.ui.components.FabColumn
import com.farmmathbuilder.app.ui.components.FarmGridCanvas
import com.farmmathbuilder.app.ui.components.HarvestAllButton
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
    LaunchedEffect(uiState.cowFeedSnackbar) {
        uiState.cowFeedSnackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeCowFeedSnackbar()
        }
    }
    LaunchedEffect(uiState.cowShopSnackbar) {
        uiState.cowShopSnackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeCowShopSnackbar()
        }
    }
    LaunchedEffect(uiState.cowDiedSnackbar) {
        uiState.cowDiedSnackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeCowDiedSnackbar()
        }
    }
    LaunchedEffect(uiState.decorationSnackbar) {
        uiState.decorationSnackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeDecorationSnackbar()
        }
    }
    // Fires for both a single harvest and "harvest all" — both set this same field
    // on success, so one hook covers both entry points.
    LaunchedEffect(uiState.showHarvestCelebrationForCellId) {
        if (uiState.showHarvestCelebrationForCellId != null) {
            SoundManager.playSfx(SoundManager.Sounds.HARVEST)
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
                modifier = Modifier.fillMaxSize(),
                cows = uiState.cows,
                onCowTapped = { animalId -> viewModel.feedCow(animalId) },
                decorations = uiState.decorations,
                placingDecorationType = uiState.placingDecorationType,
                onDecorationPlacementTarget = { side, fraction ->
                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                    viewModel.onDecorationPlacementTarget(side, fraction)
                }
            )

            val matureCount = uiState.cells.count { it.isMature }
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TopHud(player = uiState.player)

                if (matureCount > 0) {
                    HarvestAllButton(
                        matureCount = matureCount,
                        onClick = { viewModel.harvestAll() },
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            AnimalShopColumn(
                canBuyCow = viewModel.canBuyCow(),
                onBuyCow = {
                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                    viewModel.buyCow()
                },
                onOpenDecorationShop = {
                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                    viewModel.openDecorationPicker()
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )

            FabColumn(
                onChallengeClick = {
                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                    viewModel.startChallenge()
                },
                onMathClick = {
                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                    viewModel.openExercise()
                },
                onStatsClick = {
                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                    showStats = true
                },
                onSettingsClick = onOpenSettings,
                modifier = Modifier.align(Alignment.BottomEnd)
            )

            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val expansionCost = viewModel.nextExpansionCost()
                ExpandMapButton(
                    cost = expansionCost,
                    canAfford = (uiState.player?.wheatCurrency ?: 0) >= expansionCost,
                    onClick = {
                        SoundManager.playSfx(SoundManager.Sounds.CLICK)
                        viewModel.expandGrid()
                    }
                )
            }

            if (uiState.decorationPickerOpen) {
                DecorationPickerDialog(
                    onDismiss = {
                        SoundManager.playSfx(SoundManager.Sounds.DIALOG_CLOSE)
                        viewModel.dismissDecorationPicker()
                    },
                    onPick = { type ->
                        SoundManager.playSfx(SoundManager.Sounds.CLICK)
                        viewModel.startPlacingDecoration(type)
                    }
                )
            }

            if (uiState.placingDecorationType != null) {
                DecorationPlacementBanner(
                    onCancel = {
                        SoundManager.playSfx(SoundManager.Sounds.BACK)
                        viewModel.cancelPlacingDecoration()
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
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
                        carrotUnlocked = viewModel.isCarrotUnlocked(),
                        carrotUnlockHarvestsRemaining = (FarmRepository.CARROT_UNLOCK_WHEAT_HARVESTS - (uiState.player?.wheatHarvestedTotal ?: 0)).coerceAtLeast(0),
                        onDismiss = {
                            SoundManager.playSfx(SoundManager.Sounds.DIALOG_CLOSE)
                            viewModel.dismissCellPopup()
                        },
                        onPlantCrop = { cropType ->
                            SoundManager.playSfx(SoundManager.Sounds.PLANT)
                            viewModel.buildFreeOrExtra(cellId, cropType)
                        },
                        onSolveExercise = {
                            viewModel.dismissCellPopup()
                            viewModel.openExercise()
                        },
                        onCancelGrowth = {
                            SoundManager.playSfx(SoundManager.Sounds.CANCEL_GROWTH)
                            viewModel.cancelGrowth(cellId)
                        },
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
                    onClose = {
                        SoundManager.playSfx(SoundManager.Sounds.DIALOG_CLOSE)
                        viewModel.closeExercise()
                    }
                )
            }

            uiState.activeChallengeExercise?.let { exercise ->
                ChallengeDialog(
                    exercise = exercise,
                    correctCount = uiState.challengeCorrectCount,
                    challengeLength = FarmRepository.EXERCISE_STREAK_CHALLENGE_LENGTH,
                    lastAnswerCorrect = uiState.challengeLastAnswerCorrect,
                    completedBonusFields = uiState.challengeCompletedBonusFields,
                    failed = uiState.challengeFailed,
                    onAnswer = { viewModel.submitChallengeAnswer(it) },
                    onNextQuestion = { viewModel.nextChallengeQuestion() },
                    onClose = {
                        SoundManager.playSfx(SoundManager.Sounds.DIALOG_CLOSE)
                        viewModel.closeChallenge()
                    }
                )
            }

            uiState.showHarvestCelebrationForCellId?.let {
                ConfettiOverlay(onFinished = { viewModel.clearHarvestCelebration() })
            }

            if (showStats) {
                StatsDialog(
                    player = uiState.player,
                    onDismiss = {
                        SoundManager.playSfx(SoundManager.Sounds.DIALOG_CLOSE)
                        showStats = false
                    }
                )
            }
        }
    }
}
