package com.farmmathbuilder.app.domain

/** UI-facing cell state: raw persisted data + growth phase/progress derived "now". */
data class UiCell(
    val id: Int,
    val col: Int,
    val row: Int,
    val occupantType: OccupantType,
    val growthPhase: GrowthPhase,
    val growthProgress: Float,
    val plantedAtTimestamp: Long,
    val growthDurationMs: Long,
    val pathType: PathType?,
    val pathRotationDegrees: Int,
    val isBuildable: Boolean
) {
    val isEmpty: Boolean get() = occupantType == OccupantType.EMPTY
    val isMature: Boolean get() = occupantType.isCrop() && growthPhase == GrowthPhase.MATURE
    val isGrowing: Boolean get() = occupantType.isCrop() && growthPhase != GrowthPhase.MATURE && growthPhase != GrowthPhase.NONE
}

/** What kind of daily slot a build attempt would consume, per the BR decision table. */
enum class SlotAvailability {
    FREE_SLOT_AVAILABLE,
    EXTRA_SLOT_AVAILABLE,
    NONE_AVAILABLE
}

/** UI-facing animal state: raw persisted [AnimalEntity] fields + growth stage/
 * hunger derived "now", same shape as [UiCell] for crops. */
data class AnimalUiModel(
    val id: Int,
    val type: AnimalType,
    val stage: AnimalGrowthStage,
    val isHungry: Boolean
)
