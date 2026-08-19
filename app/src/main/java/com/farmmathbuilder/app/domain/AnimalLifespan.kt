package com.farmmathbuilder.app.domain

/**
 * A cow's lifespan is derived from a stored spawnedAtTimestamp, never a
 * decrementing counter — same kill-safe timestamp pattern as [AnimalGrowth]/
 * [CowHunger]/[GrowthCalculator]. Deliberately keyed off [AnimalEntity.spawnedAtTimestamp]
 * rather than [AnimalEntity.bornAtTimestamp]: the latter is backdated on
 * purchase/seed so the cow starts as an adult, which would make her die
 * instantly if lifespan used it too.
 */
object AnimalLifespan {
    /** Founder-chosen: a cow lives 2 real days before dying. */
    const val LIFESPAN_MS: Long = 2 * 24 * 60 * 60 * 1000L

    fun isDead(spawnedAtTimestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - spawnedAtTimestamp >= LIFESPAN_MS
}
