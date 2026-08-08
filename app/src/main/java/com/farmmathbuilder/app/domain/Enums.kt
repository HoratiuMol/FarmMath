package com.farmmathbuilder.app.domain

/** What currently occupies a grid cell. */
enum class OccupantType {
    EMPTY,
    WHEAT,
    PATH,
    BUILDING
}

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
