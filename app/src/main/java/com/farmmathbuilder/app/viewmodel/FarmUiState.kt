package com.farmmathbuilder.app.viewmodel

import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.domain.Exercise
import com.farmmathbuilder.app.domain.GridConfig
import com.farmmathbuilder.app.domain.UiCell

enum class TutorialStep {
    AGE_SELECT,
    GUIDED_BUILD,
    WAIT_GROWTH,
    GUIDED_HARVEST,
    GUIDED_EXERCISE,
    DONE
}

data class FarmUiState(
    val isLoading: Boolean = true,
    val cells: List<UiCell> = emptyList(),
    val player: PlayerEntity? = null,
    val settings: SettingsEntity? = null,
    val gridConfig: GridConfig = GridConfig.BASE,
    val needsOnboarding: Boolean = false,
    val tutorialStep: TutorialStep = TutorialStep.AGE_SELECT,
    val tutorialFirstFieldCellId: Int? = null,
    val selectedCellId: Int? = null,
    val activeExercise: Exercise? = null,
    val exerciseIsGuidedTutorial: Boolean = false,
    /** Non-null: this exercise reduces this cell's growth time instead of granting an extra field. */
    val exercisePurposeCellId: Int? = null,
    val lastAnswerCorrect: Boolean? = null,
    val showHarvestCelebrationForCellId: Int? = null,
    val dailyResetSnackbar: Boolean = false,
    val noSlotsSnackbar: Boolean = false,
    val expandGridSnackbar: String? = null
)
