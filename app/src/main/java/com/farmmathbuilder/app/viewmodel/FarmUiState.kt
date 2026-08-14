package com.farmmathbuilder.app.viewmodel

import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.domain.Exercise
import com.farmmathbuilder.app.domain.GridConfig
import com.farmmathbuilder.app.domain.UiCell

data class FarmUiState(
    val isLoading: Boolean = true,
    val cells: List<UiCell> = emptyList(),
    val player: PlayerEntity? = null,
    val settings: SettingsEntity? = null,
    val gridConfig: GridConfig = GridConfig.BASE,
    val selectedCellId: Int? = null,
    val activeExercise: Exercise? = null,
    /** Non-null: this exercise reduces this cell's growth time instead of granting an extra field. */
    val exercisePurposeCellId: Int? = null,
    val lastAnswerCorrect: Boolean? = null,
    val showHarvestCelebrationForCellId: Int? = null,
    val dailyResetSnackbar: Boolean = false,
    val noSlotsSnackbar: Boolean = false,
    val expandGridSnackbar: String? = null,
    /** True while the player is picking a new spot for the barn (see FarmViewModel
     * startRepositioningBuilding/onRepositionTarget) — grid taps place the building
     * instead of their normal cell action while this is true. */
    val isRepositioningBuilding: Boolean = false,
    val moveBuildingSnackbar: String? = null,
    /** Recomputed every 1s tick from player.cowLastFedTimestamp (see CowHunger) —
     * drives the floating hunger icon and whether the cow is tappable at all. */
    val isCowHungry: Boolean = false,
    /** Non-null right after tapping the hungry cow with zero carrotInventory —
     * feeding costs 1 carrot, see FarmRepository.feedCow. */
    val cowFeedSnackbar: String? = null,

    // ---------- Dedicated "10 in a row" Challenge (see ChallengeDialog) ----------
    // Entirely separate state from activeExercise/lastAnswerCorrect above — the
    // founder explicitly asked for this not to be folded into the casual
    // single-exercise flow. Ephemeral/in-memory only (not persisted), same as
    // activeExercise itself: a challenge attempt is a one-sitting activity.
    val activeChallengeExercise: Exercise? = null,
    val challengeCorrectCount: Int = 0,
    val challengeLastAnswerCorrect: Boolean? = null,
    /** Non-null once the current attempt reaches full length — the bonus-pack size. */
    val challengeCompletedBonusFields: Int? = null,
    /** True once a wrong answer has ended the current attempt with no reward. */
    val challengeFailed: Boolean = false
)
