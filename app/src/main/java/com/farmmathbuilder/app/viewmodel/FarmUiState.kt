package com.farmmathbuilder.app.viewmodel

import com.farmmathbuilder.app.data.entity.DecorationEntity
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.domain.AnimalUiModel
import com.farmmathbuilder.app.domain.DecorationType
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
    /** Every owned animal (currently only cows), stage/hunger recomputed every 1s
     * tick from their own timestamps (see AnimalGrowth/CowHunger) — drives the
     * wandering herd's rendering and which ones are currently tappable/hungry. */
    val cows: List<AnimalUiModel> = emptyList(),
    /** Current population cap (FarmRepository.maxCows) — 5 base, +5 per map expansion. */
    val maxCows: Int = 5,
    /** Non-null right after tapping a hungry cow with zero carrotInventory —
     * feeding costs 1 carrot, see FarmRepository.feedAnimal. */
    val cowFeedSnackbar: String? = null,
    /** Non-null right after a failed/successful cow purchase (see FarmScreen's
     * animal shop icon column and FarmViewModel.buyCow). */
    val cowShopSnackbar: String? = null,
    /** Non-null right after one or more cows reach the end of their 20-real-minute
     * lifespan and are removed (see AnimalLifespan/FarmRepository.removeDeadAnimals). */
    val cowDiedSnackbar: String? = null,

    // ---------- Map decorations ("accidentes geográficos" shop) ----------
    /** Every placed decoration (currently only ever at most one RIVER — see
     * FarmRepository.placeDecoration) — re-projected onto the current
     * gridConfig every frame by FarmGridCanvas, so it always renders outside
     * the fence and in the same relative spot through map expansions. */
    val decorations: List<DecorationEntity> = emptyList(),
    /** Non-null while the decoration picker dialog is open (tapped the terrain
     * shop icon, haven't picked a type yet). */
    val decorationPickerOpen: Boolean = false,
    /** Non-null while placement mode is active: the player picked this type from
     * the picker and must now tap a spot outside the fence to place it. */
    val placingDecorationType: DecorationType? = null,
    /** Non-null right after a placement attempt (success or capped-out failure
     * — see FarmRepository.placeDecoration/maxDecorations). */
    val decorationSnackbar: String? = null,

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
