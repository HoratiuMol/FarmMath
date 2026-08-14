package com.farmmathbuilder.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.data.repository.FarmRepository
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.domain.CowHunger
import com.farmmathbuilder.app.domain.GridConfig
import com.farmmathbuilder.app.domain.GridMath
import com.farmmathbuilder.app.domain.GrowthCalculator
import com.farmmathbuilder.app.domain.GrowthPhase
import com.farmmathbuilder.app.domain.MathExerciseGenerator
import com.farmmathbuilder.app.domain.OccupantType
import com.farmmathbuilder.app.domain.isCrop
import com.farmmathbuilder.app.domain.PathType
import com.farmmathbuilder.app.domain.SlotAvailability
import com.farmmathbuilder.app.domain.TextSizeOption
import com.farmmathbuilder.app.domain.UiCell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FarmViewModel(private val repository: FarmRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FarmUiState())
    val uiState: StateFlow<FarmUiState> = _uiState.asStateFlow()

    /** Ticks once a second so growth phases/progress recompute live from timestamps. */
    private val ticker = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            repository.initializeAll()
            val resetHappened = repository.checkAndApplyDailyReset()
            if (resetHappened) {
                _uiState.value = _uiState.value.copy(dailyResetSnackbar = true)
            }
            observeState()
        }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                ticker.value = System.currentTimeMillis()
            }
        }
    }

    private fun observeState() {
        viewModelScope.launch {
            combine(repository.cells, repository.player, repository.settings, ticker) { cells, player, settings, now ->
                val gridConfig = player?.let {
                    GridConfig(
                        cols = it.gridCols,
                        rows = it.gridRows,
                        buildableRadius = it.buildableRadius,
                        buildingAnchorCol = it.buildingAnchorCol ?: GridMath.defaultBuildingAnchorCol(it.gridCols),
                        buildingAnchorRow = it.buildingAnchorRow ?: GridMath.defaultBuildingAnchorRow(it.gridRows)
                    )
                } ?: GridConfig.BASE
                val uiCells = cells.map { cell ->
                    val phase = if (cell.occupantType.isCrop()) {
                        GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs, now.takeIf { it > 0 } ?: System.currentTimeMillis())
                    } else GrowthPhase.NONE
                    val progress = if (cell.occupantType.isCrop()) {
                        GrowthCalculator.computeProgress(cell.plantedAtTimestamp, cell.growthDurationMs, now.takeIf { it > 0 } ?: System.currentTimeMillis())
                    } else 0f
                    UiCell(
                        id = cell.id,
                        col = cell.col,
                        row = cell.row,
                        occupantType = cell.occupantType,
                        growthPhase = phase,
                        growthProgress = progress,
                        plantedAtTimestamp = cell.plantedAtTimestamp,
                        growthDurationMs = cell.growthDurationMs,
                        pathType = cell.pathType,
                        pathRotationDegrees = cell.pathRotationDegrees,
                        isBuildingCell = GridMath.isBuildingCell(cell.col, cell.row, gridConfig.buildingAnchorCol, gridConfig.buildingAnchorRow),
                        isWithinBuildableRadius = GridMath.isWithinBuildableRadius(cell.col, cell.row, gridConfig.cols, gridConfig.rows, gridConfig.buildableRadius, gridConfig.buildingAnchorCol, gridConfig.buildingAnchorRow)
                    )
                }
                val cowHungry = player?.let {
                    CowHunger.isHungry(it.cowLastFedTimestamp, now.takeIf { n -> n > 0 } ?: System.currentTimeMillis())
                } ?: false
                Triple(uiCells, player, settings) to (gridConfig to cowHungry)
            }.collect { (data, extra) ->
                val (uiCells, player, settings) = data
                val (gridConfig, cowHungry) = extra
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    cells = uiCells,
                    player = player,
                    settings = settings,
                    gridConfig = gridConfig,
                    isCowHungry = cowHungry
                )
            }
        }
    }

    // ---------- Cell interaction ----------

    fun onCellTapped(cellId: Int) {
        if (_uiState.value.isRepositioningBuilding) return
        val cell = _uiState.value.cells.find { it.id == cellId } ?: return
        if (cell.isBuildingCell) return
        if (cell.isEmpty && !cell.isWithinBuildableRadius) return // locked cell, no-op
        if (cell.isMature) {
            harvest(cellId)
            return
        }
        _uiState.value = _uiState.value.copy(selectedCellId = cellId)
    }

    fun dismissCellPopup() {
        _uiState.value = _uiState.value.copy(selectedCellId = null)
    }

    fun buildFreeOrExtra(cellId: Int, cropType: OccupantType) {
        viewModelScope.launch {
            val player = repository.currentPlayer() ?: return@launch
            val availability = repository.slotAvailability(player)
            if (availability == SlotAvailability.NONE_AVAILABLE) {
                _uiState.value = _uiState.value.copy(noSlotsSnackbar = true, selectedCellId = null)
                return@launch
            }
            repository.plantCrop(cellId, cropType)
            _uiState.value = _uiState.value.copy(selectedCellId = null)
        }
    }

    /** Carrot unlocks after [FarmRepository.CARROT_UNLOCK_HARVESTS] total harvests —
     * exposed for the plant-crop dialog's lock hint/messaging. */
    fun isCarrotUnlocked(): Boolean = _uiState.value.player?.let { repository.isCarrotUnlocked(it) } ?: false

    fun cancelGrowth(cellId: Int) {
        viewModelScope.launch {
            repository.cancelGrowth(cellId)
            _uiState.value = _uiState.value.copy(selectedCellId = null)
        }
    }

    fun harvest(cellId: Int) {
        viewModelScope.launch {
            val ok = repository.harvest(cellId)
            if (ok) {
                _uiState.value = _uiState.value.copy(
                    selectedCellId = null,
                    showHarvestCelebrationForCellId = cellId
                )
            }
        }
    }

    /** Harvests every currently-mature cell in one action. */
    fun harvestAll() {
        viewModelScope.launch {
            val lastMatureCellId = _uiState.value.cells.lastOrNull { it.isMature }?.id
            val harvestedCount = repository.harvestAll()
            if (harvestedCount > 0 && lastMatureCellId != null) {
                _uiState.value = _uiState.value.copy(showHarvestCelebrationForCellId = lastMatureCellId)
            }
        }
    }

    fun clearHarvestCelebration() {
        _uiState.value = _uiState.value.copy(showHarvestCelebrationForCellId = null)
    }

    fun buildPath(cellId: Int, pathType: PathType, rotation: Int = 0) {
        viewModelScope.launch {
            repository.buildPath(cellId, pathType, rotation)
            _uiState.value = _uiState.value.copy(selectedCellId = null)
        }
    }

    fun removePath(cellId: Int) {
        viewModelScope.launch {
            repository.removePath(cellId)
            _uiState.value = _uiState.value.copy(selectedCellId = null)
        }
    }

    fun consumeSnackbars() {
        _uiState.value = _uiState.value.copy(dailyResetSnackbar = false, noSlotsSnackbar = false)
    }

    // ---------- Map expansion ----------

    /** Cost shown in the UI for the *next* expansion, given the current grid state. */
    fun nextExpansionCost(): Int = 100 * ((_uiState.value.player?.gridExpansionLevel ?: 0) + 1)

    fun expandGrid() {
        viewModelScope.launch {
            val player = repository.currentPlayer() ?: return@launch
            val cost = 100 * (player.gridExpansionLevel + 1)
            if (player.wheatCurrency < cost) {
                _uiState.value = _uiState.value.copy(
                    expandGridSnackbar = "Not enough wheat — need ${cost - player.wheatCurrency} more"
                )
                return@launch
            }
            val ok = repository.expandGrid()
            _uiState.value = _uiState.value.copy(
                expandGridSnackbar = if (ok) "Map expanded!" else "Not enough wheat — need ${cost - player.wheatCurrency} more"
            )
        }
    }

    fun consumeExpandGridSnackbar() {
        _uiState.value = _uiState.value.copy(expandGridSnackbar = null)
    }

    // ---------- Move barn ----------

    /** Whether "move barn" can currently be offered at all — no crop (wheat or
     * carrot) growing/mature anywhere on the map (see
     * FarmRepository.canRepositionBuilding's doc). */
    fun hasGrowingCropOnMap(): Boolean = _uiState.value.cells.any { it.occupantType.isCrop() }

    fun startRepositioningBuilding() {
        if (hasGrowingCropOnMap()) {
            _uiState.value = _uiState.value.copy(
                moveBuildingSnackbar = "Harvest or cancel all crops first to move the barn"
            )
            return
        }
        _uiState.value = _uiState.value.copy(isRepositioningBuilding = true, selectedCellId = null)
    }

    fun cancelRepositioningBuilding() {
        _uiState.value = _uiState.value.copy(isRepositioningBuilding = false)
    }

    /** Called when the player taps a cell while in "move barn" mode: that cell
     * becomes the new top-left anchor of the building's 2x2 footprint. */
    fun onRepositionTarget(col: Int, row: Int) {
        viewModelScope.launch {
            val moved = repository.moveBuilding(col, row)
            _uiState.value = _uiState.value.copy(
                isRepositioningBuilding = false,
                moveBuildingSnackbar = if (moved) null else "Can't place the barn there"
            )
        }
    }

    fun consumeMoveBuildingSnackbar() {
        _uiState.value = _uiState.value.copy(moveBuildingSnackbar = null)
    }

    // ---------- Cow ----------

    /** Tapping the cow while she's hungry (FarmGridCanvas only fires this when
     * [FarmUiState.isCowHungry] is true) feeds her 1 harvested carrot and resets
     * her hunger timer. If the player has none, nothing changes and a hint
     * snackbar explains why (harvest a carrot first). */
    fun feedCow() {
        viewModelScope.launch {
            val fed = repository.feedCow()
            if (!fed) {
                _uiState.value = _uiState.value.copy(
                    cowFeedSnackbar = "🥕 You need a carrot to feed her — harvest one first!"
                )
            }
        }
    }

    fun consumeCowFeedSnackbar() {
        _uiState.value = _uiState.value.copy(cowFeedSnackbar = null)
    }

    // ---------- Math exercise (casual single-exercise-for-a-field flow) ----------

    fun openExercise() {
        val ageBand = _uiState.value.player?.ageBand ?: AgeBand.AGE_6_9
        val exercise = MathExerciseGenerator.generate(ageBand)
        _uiState.value = _uiState.value.copy(
            activeExercise = exercise,
            exercisePurposeCellId = null,
            lastAnswerCorrect = null
        )
    }

    /** "Solve a problem to save 1 minute": a correct answer reduces this cell's growth time, no extra field granted. */
    fun openExerciseForTimeReduction(cellId: Int) {
        val ageBand = _uiState.value.player?.ageBand ?: AgeBand.AGE_6_9
        val exercise = MathExerciseGenerator.generate(ageBand)
        _uiState.value = _uiState.value.copy(
            activeExercise = exercise,
            exercisePurposeCellId = cellId,
            lastAnswerCorrect = null,
            selectedCellId = null
        )
    }

    fun submitAnswer(answer: Int) {
        val exercise = _uiState.value.activeExercise ?: return
        val correct = answer == exercise.correctAnswer
        val timeReductionCellId = _uiState.value.exercisePurposeCellId
        viewModelScope.launch {
            if (timeReductionCellId != null) {
                if (correct) {
                    repository.reduceGrowthTime(timeReductionCellId)
                }
                _uiState.value = _uiState.value.copy(lastAnswerCorrect = correct)
            } else {
                repository.recordExerciseResult(correct)
                _uiState.value = _uiState.value.copy(lastAnswerCorrect = correct)
            }
        }
    }

    fun nextExercise() {
        val ageBand = _uiState.value.player?.ageBand ?: AgeBand.AGE_6_9
        _uiState.value = _uiState.value.copy(
            activeExercise = MathExerciseGenerator.generate(ageBand),
            lastAnswerCorrect = null
        )
    }

    fun closeExercise() {
        _uiState.value = _uiState.value.copy(
            activeExercise = null,
            exercisePurposeCellId = null,
            lastAnswerCorrect = null
        )
    }

    // ---------- Dedicated "10 in a row" Challenge (separate from the flow above) ----------

    /** Starts a fresh Challenge attempt: [FarmUiState.challengeCorrectCount] resets
     * to 0 and the first question loads. Entered only via its own FAB, never as a
     * continuation of a casual exercise. */
    fun startChallenge() {
        val ageBand = _uiState.value.player?.ageBand ?: AgeBand.AGE_6_9
        _uiState.value = _uiState.value.copy(
            activeChallengeExercise = MathExerciseGenerator.generate(ageBand),
            challengeCorrectCount = 0,
            challengeLastAnswerCorrect = null,
            challengeCompletedBonusFields = null,
            challengeFailed = false
        )
    }

    fun submitChallengeAnswer(answer: Int) {
        val exercise = _uiState.value.activeChallengeExercise ?: return
        val correct = answer == exercise.correctAnswer
        viewModelScope.launch {
            repository.recordChallengeAnswer(correct)
            if (!correct) {
                _uiState.value = _uiState.value.copy(challengeLastAnswerCorrect = false, challengeFailed = true)
                return@launch
            }
            val newCount = _uiState.value.challengeCorrectCount + 1
            if (newCount >= FarmRepository.EXERCISE_STREAK_CHALLENGE_LENGTH) {
                val bonus = repository.grantChallengeBonus()
                _uiState.value = _uiState.value.copy(
                    challengeCorrectCount = newCount,
                    challengeLastAnswerCorrect = true,
                    challengeCompletedBonusFields = bonus
                )
            } else {
                _uiState.value = _uiState.value.copy(challengeCorrectCount = newCount, challengeLastAnswerCorrect = true)
            }
        }
    }

    fun nextChallengeQuestion() {
        val ageBand = _uiState.value.player?.ageBand ?: AgeBand.AGE_6_9
        _uiState.value = _uiState.value.copy(
            activeChallengeExercise = MathExerciseGenerator.generate(ageBand),
            challengeLastAnswerCorrect = null
        )
    }

    fun closeChallenge() {
        _uiState.value = _uiState.value.copy(
            activeChallengeExercise = null,
            challengeCorrectCount = 0,
            challengeLastAnswerCorrect = null,
            challengeCompletedBonusFields = null,
            challengeFailed = false
        )
    }

    // ---------- Settings ----------

    fun updateAgeBand(ageBand: AgeBand) {
        viewModelScope.launch {
            repository.setAgeBand(ageBand)
        }
    }

    fun updateSettings(mutator: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            repository.updateSettings(mutator)
        }
    }

    /** Called from Activity onPause/onStop; Room writes are already durable, this is a safety-net no-op point. */
    fun onAppBackgrounded() {
        // All mutating actions above already persist immediately via Room transactions.
        // Kept as an explicit hook per FR-073 so any future in-memory-only state has a save point.
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            val resetHappened = repository.checkAndApplyDailyReset()
            if (resetHappened) {
                _uiState.value = _uiState.value.copy(dailyResetSnackbar = true)
            }
        }
    }

    companion object {
        fun factory(repository: FarmRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FarmViewModel(repository) as T
                }
            }
    }
}
