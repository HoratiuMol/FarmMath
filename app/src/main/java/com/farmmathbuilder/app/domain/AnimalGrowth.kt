package com.farmmathbuilder.app.domain

/**
 * A calf's growth is derived from a stored bornAtTimestamp, never a decrementing
 * counter — same kill-safe pattern as [GrowthCalculator] (crops) and [CowHunger].
 */
object AnimalGrowth {
    /** Founder-chosen: a calf takes a genuine 1 real day to grow up. Deliberately
     * NOT using [GrowthCalculator.NORMAL_GROWTH_DURATION_MS]'s compressed "1 real
     * day ~= 10 minutes" scale — growing the herd is meant to be a slower,
     * longer-term payoff than a single crop cycle. */
    const val CALF_GROWTH_DURATION_MS: Long = 24 * 60 * 60 * 1000L

    fun isAdult(bornAtTimestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - bornAtTimestamp >= CALF_GROWTH_DURATION_MS

    fun stage(bornAtTimestamp: Long, now: Long = System.currentTimeMillis()): AnimalGrowthStage =
        if (isAdult(bornAtTimestamp, now)) AnimalGrowthStage.ADULT else AnimalGrowthStage.CALF
}
