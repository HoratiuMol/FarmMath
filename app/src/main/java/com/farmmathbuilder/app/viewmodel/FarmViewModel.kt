package com.farmmathbuilder.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.farmmathbuilder.app.data.entity.DecorationEntity
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.data.repository.FarmRepository
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.domain.AnimalGrowth
import com.farmmathbuilder.app.domain.AnimalUiModel
import com.farmmathbuilder.app.domain.CowHunger
import com.farmmathbuilder.app.domain.DecorationSide
import com.farmmathbuilder.app.domain.DecorationType
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
            // Founder request: remind the player every time the app opens
            // (cold start) if today's daily math mission is still unclaimed —
            // not just the completion toast, a nudge beforehand too. Reuses
            // the same dailyMissionSnackbar field/LaunchedEffect as the
            // completion message (mutually exclusive moments, so no conflict).
            val player = repository.currentPlayer()
            if (player != null && !player.dailyMissionClaimed) {
                _uiState.value = _uiState.value.copy(dailyMissionSnackbar = dailyMissionReminderMessage())
            }
            observeState()
        }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                ticker.value = now
                // Cow lifespan (founder request): 2 real days after spawning, a
                // cow dies and is removed — checked at the same 1s cadence as
                // growth/hunger recompute. See AnimalLifespan/FarmRepository.removeDeadAnimals.
                val dead = repository.removeDeadAnimals(now)
                if (dead.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        cowDiedSnackbar = if (dead.size == 1) "🐄 One of your cows has died of old age." else "🐄 ${dead.size} cows have died of old age."
                    )
                }
            }
        }
    }

    /** Bundles one combine() tick's worth of derived state — replaces an earlier
     * nested Pair/Triple now that a 5th flow (animals) is folded in too. */
    private data class FarmSnapshot(
        val uiCells: List<UiCell>,
        val player: PlayerEntity?,
        val settings: SettingsEntity?,
        val gridConfig: GridConfig,
        val cows: List<AnimalUiModel>,
        val maxCows: Int
    )

    private fun observeState() {
        viewModelScope.launch {
            val coreState = combine(repository.cells, repository.player, repository.settings, repository.animals, ticker) { cells, player, settings, animals, now ->
                val effectiveNow = now.takeIf { it > 0 } ?: System.currentTimeMillis()
                val gridConfig = player?.let {
                    GridConfig(cols = it.gridCols, rows = it.gridRows)
                } ?: GridConfig.BASE
                val uiCells = cells.map { cell ->
                    val phase = if (cell.occupantType.isCrop()) {
                        GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs, effectiveNow)
                    } else GrowthPhase.NONE
                    val progress = if (cell.occupantType.isCrop()) {
                        GrowthCalculator.computeProgress(cell.plantedAtTimestamp, cell.growthDurationMs, effectiveNow)
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
                        isBuildable = GridMath.isBuildable(cell.col, cell.row, gridConfig.cols, gridConfig.rows)
                    )
                }
                val cowModels = animals.map { animal ->
                    AnimalUiModel(
                        id = animal.id,
                        type = animal.type,
                        stage = AnimalGrowth.stage(animal.bornAtTimestamp, effectiveNow),
                        isHungry = CowHunger.isHungry(animal.lastFedTimestamp, effectiveNow)
                    )
                }
                val maxCowsValue = player?.let { repository.maxCows(it) } ?: FarmRepository.COW_BASE_CAP
                FarmSnapshot(uiCells, player, settings, gridConfig, cowModels, maxCowsValue)
            }

            // Chained as a second combine() (rather than folded into the 5-flow one
            // above) since kotlinx.coroutines' typed combine() only goes up to 5
            // differently-typed flows, and decorations is a 6th.
            coreState.combine(repository.decorations) { snapshot, decorations ->
                snapshot to decorations
            }.collect { (snapshot, decorations) ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    cells = snapshot.uiCells,
                    player = snapshot.player,
                    settings = snapshot.settings,
                    gridConfig = snapshot.gridConfig,
                    cows = snapshot.cows,
                    maxCows = snapshot.maxCows,
                    decorations = decorations
                )
            }
        }
    }

    // ---------- Cell interaction ----------

    fun onCellTapped(cellId: Int) {
        val cell = _uiState.value.cells.find { it.id == cellId } ?: return
        if (cell.isEmpty && !cell.isBuildable) return // locked cell, no-op
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

    // ---------- Cows (shop + feeding + breeding) ----------

    /** Tapping a cow while she's hungry (FarmGridCanvas only fires this for a
     * cow whose own [com.farmmathbuilder.app.domain.AnimalUiModel.isHungry] is
     * true) feeds her 1 harvested carrot and resets her hunger timer. If the
     * player has none, nothing changes and a hint snackbar explains why. Every
     * 2nd feed (any cow) also rolls a breeding check server-side — a success
     * surfaces its own snackbar (see FarmRepository.feedAnimal). */
    fun feedCow(animalId: Int) {
        viewModelScope.launch {
            val result = repository.feedAnimal(animalId)
            _uiState.value = _uiState.value.copy(
                cowFeedSnackbar = when {
                    !result.fed -> "🥕 You need a carrot to feed her — harvest one first!"
                    result.calfBorn -> "🐄 A new calf was born!"
                    else -> null
                }
            )
        }
    }

    fun consumeCowFeedSnackbar() {
        _uiState.value = _uiState.value.copy(cowFeedSnackbar = null)
    }

    fun canBuyCow(): Boolean {
        val state = _uiState.value
        val player = state.player ?: return false
        return player.wheatCurrency >= FarmRepository.COW_COST && state.cows.size < state.maxCows
    }

    /** Buys one adult cow for [FarmRepository.COW_COST] wheat — the top-right
     * animal-shop icon column's tap action (see HudOverlay.AnimalShopColumn). */
    fun buyCow() {
        viewModelScope.launch {
            val state = _uiState.value
            val player = state.player
            val ok = repository.buyCow()
            _uiState.value = _uiState.value.copy(
                cowShopSnackbar = when {
                    ok -> "🐄 New cow purchased!"
                    player == null -> null
                    state.cows.size >= state.maxCows -> "Cow pen is full — expand the map for more room"
                    player.wheatCurrency < FarmRepository.COW_COST -> "Not enough wheat — need ${FarmRepository.COW_COST - player.wheatCurrency} more"
                    else -> null
                }
            )
        }
    }

    fun consumeCowShopSnackbar() {
        _uiState.value = _uiState.value.copy(cowShopSnackbar = null)
    }

    fun consumeCowDiedSnackbar() {
        _uiState.value = _uiState.value.copy(cowDiedSnackbar = null)
    }

    // ---------- Map decorations ("accidentes geográficos" shop) ----------

    fun openDecorationPicker() {
        _uiState.value = _uiState.value.copy(decorationPickerOpen = true)
    }

    fun dismissDecorationPicker() {
        _uiState.value = _uiState.value.copy(decorationPickerOpen = false)
    }

    /** Player picked a decoration type from the picker dialog — closes it and
     * enters placement mode; the next valid tap on the grid (outside the fence,
     * see FarmGridCanvas) places it there. */
    fun startPlacingDecoration(type: DecorationType) {
        _uiState.value = _uiState.value.copy(decorationPickerOpen = false, placingDecorationType = type)
    }

    fun cancelPlacingDecoration() {
        _uiState.value = _uiState.value.copy(placingDecorationType = null)
    }

    /** Called when the player taps a valid outside-the-fence spot while in
     * placement mode. [side]/[alongFraction] are already resolved from the raw
     * tap by FarmGridCanvas (nearest border edge + how far along it), so this
     * just has to persist them — see FarmRepository.placeDecoration's doc for
     * why storing them relative (rather than an absolute col/row) is what keeps
     * the decoration outside the fence and in the same relative spot through
     * future map expansions. */
    fun onDecorationPlacementTarget(side: DecorationSide, alongFraction: Float) {
        val type = _uiState.value.placingDecorationType ?: return
        viewModelScope.launch {
            val placed = repository.placeDecoration(type, side, alongFraction)
            _uiState.value = _uiState.value.copy(
                placingDecorationType = null,
                decorationSnackbar = if (placed) null else "Expand the map to unlock another decoration slot"
            )
        }
    }

    fun consumeDecorationSnackbar() {
        _uiState.value = _uiState.value.copy(decorationSnackbar = null)
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
                val missionCompleted = repository.recordExerciseResult(correct)
                _uiState.value = _uiState.value.copy(
                    lastAnswerCorrect = correct,
                    dailyMissionSnackbar = if (missionCompleted) dailyMissionCompleteMessage() else _uiState.value.dailyMissionSnackbar
                )
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

    /** Daily math mission (gameplay push: "misión del día") — solve
     * [FarmRepository.DAILY_MISSION_TARGET] problems today, from either the
     * casual or Challenge flow, for a one-time bonus (see
     * FarmRepository.recordExerciseResult/recordChallengeAnswer/
     * applyDailyMission). This message is shared by both flows. */
    private fun dailyMissionCompleteMessage(): String =
        "🎯 Daily mission complete! +${FarmRepository.DAILY_MISSION_STAR_BONUS} ⭐ bonus"

    /** Shown once per app open (see init above) while today's mission is
     * still unclaimed — a nudge, not a scold: no penalty is implied, it just
     * names the reward waiting for them. */
    private fun dailyMissionReminderMessage(): String =
        "🎯 Today's math mission: solve ${FarmRepository.DAILY_MISSION_TARGET} for +${FarmRepository.DAILY_MISSION_STAR_BONUS} ⭐!"

    fun consumeDailyMissionSnackbar() {
        _uiState.value = _uiState.value.copy(dailyMissionSnackbar = null)
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
            val missionCompleted = repository.recordChallengeAnswer(correct)
            val missionSnackbar = if (missionCompleted) dailyMissionCompleteMessage() else _uiState.value.dailyMissionSnackbar
            if (!correct) {
                _uiState.value = _uiState.value.copy(
                    challengeLastAnswerCorrect = false,
                    challengeFailed = true,
                    dailyMissionSnackbar = missionSnackbar
                )
                return@launch
            }
            val newCount = _uiState.value.challengeCorrectCount + 1
            if (newCount >= FarmRepository.EXERCISE_STREAK_CHALLENGE_LENGTH) {
                val bonus = repository.grantChallengeBonus()
                _uiState.value = _uiState.value.copy(
                    challengeCorrectCount = newCount,
                    challengeLastAnswerCorrect = true,
                    challengeCompletedBonusFields = bonus,
                    dailyMissionSnackbar = missionSnackbar
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    challengeCorrectCount = newCount,
                    challengeLastAnswerCorrect = true,
                    dailyMissionSnackbar = missionSnackbar
                )
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

    /** Settings menu "New world" (founder request): wipes the save and starts over
     * from a fresh grid/player/herd — see FarmRepository.resetWorld's doc for what's
     * kept (settings) vs wiped. Also clears every piece of transient/dialog UI state
     * that no longer makes sense against a blank world, so the next recomposition
     * doesn't briefly show a stale popup/exercise pointing at a cell/animal that no
     * longer exists.
     */
    fun resetWorld() {
        viewModelScope.launch {
            repository.resetWorld()
            _uiState.value = _uiState.value.copy(
                selectedCellId = null,
                activeExercise = null,
                exercisePurposeCellId = null,
                lastAnswerCorrect = null,
                showHarvestCelebrationForCellId = null,
                decorationPickerOpen = false,
                placingDecorationType = null,
                decorationSnackbar = null,
                activeChallengeExercise = null,
                challengeCorrectCount = 0,
                challengeLastAnswerCorrect = null,
                challengeCompletedBonusFields = null,
                challengeFailed = false
            )
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
