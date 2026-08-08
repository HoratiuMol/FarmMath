package com.farmmathbuilder.app.domain

/**
 * Growth is always derived from a stored plantedAtTimestamp + a growth duration,
 * NEVER from a decrementing in-memory counter (NFR-001 / FR-017). This makes the
 * timer immune to process death: on any read we simply compare "now" to the
 * persisted timestamp.
 */
object GrowthCalculator {

    /** Real MVP growth duration: 10 minutes (FR-012). */
    const val NORMAL_GROWTH_DURATION_MS: Long = 10 * 60 * 1000L

    /** R-9: shortened growth duration for the very first field planted during tutorial. */
    const val TUTORIAL_GROWTH_DURATION_MS: Long = 60 * 1000L

    /**
     * Given the timestamp a seed was planted and the duration it takes to mature,
     * compute the current growth phase. Pure function of (now, plantedAt, duration).
     */
    fun computePhase(plantedAtTimestamp: Long, growthDurationMs: Long, now: Long = System.currentTimeMillis()): GrowthPhase {
        if (plantedAtTimestamp <= 0L) return GrowthPhase.NONE
        val elapsed = (now - plantedAtTimestamp).coerceAtLeast(0L)
        val ratio = if (growthDurationMs <= 0L) 1.0 else elapsed.toDouble() / growthDurationMs.toDouble()
        return when {
            ratio >= 1.0 -> GrowthPhase.MATURE
            ratio >= 0.66 -> GrowthPhase.PLANT
            ratio >= 0.33 -> GrowthPhase.SPROUT
            else -> GrowthPhase.SEED
        }
    }

    /** Progress fraction 0f..1f towards maturity, used for progress rings / animations. */
    fun computeProgress(plantedAtTimestamp: Long, growthDurationMs: Long, now: Long = System.currentTimeMillis()): Float {
        if (plantedAtTimestamp <= 0L || growthDurationMs <= 0L) return 0f
        val elapsed = (now - plantedAtTimestamp).coerceAtLeast(0L)
        return (elapsed.toDouble() / growthDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun remainingMillis(plantedAtTimestamp: Long, growthDurationMs: Long, now: Long = System.currentTimeMillis()): Long {
        if (plantedAtTimestamp <= 0L) return 0L
        val elapsed = (now - plantedAtTimestamp).coerceAtLeast(0L)
        return (growthDurationMs - elapsed).coerceAtLeast(0L)
    }
}
