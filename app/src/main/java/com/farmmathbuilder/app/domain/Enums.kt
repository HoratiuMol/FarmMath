package com.farmmathbuilder.app.domain

/** What currently occupies a grid cell. */
enum class OccupantType {
    EMPTY,
    WHEAT,
    PATH,
    CARROT
}

/** WHEAT and CARROT are both real growable crops — they share every growth-phase,
 * harvest, cancel, and "solve to save time" rule; PATH/EMPTY are not. Single
 * source of truth so a future third crop only needs one line changed here. */
fun OccupantType.isCrop(): Boolean = this == OccupantType.WHEAT || this == OccupantType.CARROT

/** Growth phase of a Wheat occupant. NONE is used for non-wheat cells. */
enum class GrowthPhase {
    NONE,
    SEED,
    SPROUT,
    PLANT,
    MATURE
}

/** Math operation types an exercise can use (expanded per age band). */
enum class MathOperation {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE
}

/**
 * Age band — originally chosen once at onboarding (R-6 / R-10), now also
 * editable later from Settings (also controls math difficulty).
 */
enum class AgeBand(
    val label: String,
    val minOperand: Int,
    val maxOperand: Int,
    val operations: List<MathOperation>
) {
    AGE_6_9("6-9", 1, 10, listOf(MathOperation.ADD, MathOperation.SUBTRACT)),
    AGE_10_12(
        "10-12",
        1,
        50,
        listOf(MathOperation.ADD, MathOperation.SUBTRACT, MathOperation.MULTIPLY, MathOperation.DIVIDE)
    )
}

enum class TextSizeOption(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1.0f),
    LARGE("Large", 1.25f)
}

/** Path piece shapes (R-1: straight/corner/T only, no full intersection art). */
enum class PathType {
    STRAIGHT,
    CORNER,
    T_JUNCTION
}

/** Owned-animal species — only COW exists today, but the field/table is
 * already shaped for more (see AnimalEntity), per the founder's "más
 * animales" direction. */
enum class AnimalType {
    COW
}

/** Derived (never persisted) from an animal's bornAtTimestamp — see
 * [AnimalGrowth]. Mirrors how [GrowthPhase] is derived for crops. */
enum class AnimalGrowthStage {
    CALF,
    ADULT
}

/** Player-placeable map decorations (founder request 2026-08-18: a "geographic
 * features" shop, separate from the animal shop) — purely cosmetic, drawn
 * outside the fenced play area. Only RIVER exists today, but the type/table are
 * already shaped for more, same "one enum, room to grow" pattern as [AnimalType]. */
enum class DecorationType {
    RIVER,
    CAVE
}

/** Which border edge a [DecorationType] is attached to — see DecorationEntity.
 * Combined with alongFraction (0..1 position along that edge) this fully
 * determines the decoration's placement, and is re-projected onto the grid's
 * current cols/rows every frame (see FarmGridCanvas), so it automatically
 * stays outside the fence and in the same relative spot as the map expands. */
enum class DecorationSide {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT
}
