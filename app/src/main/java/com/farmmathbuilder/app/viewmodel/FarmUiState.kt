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
    val moveBuildingSnackbar: String? = null
)
