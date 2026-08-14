package com.farmmathbuilder.app.domain

/**
 * The wandering cow's hunger is derived from a stored last-fed timestamp, never a
 * decrementing in-memory counter — the same kill-safe pattern [GrowthCalculator]
 * uses for crop timers, so hunger state survives app kill/background correctly.
 * The cow has no other gameplay effect: feeding is free (tap the cow while hungry),
 * just a small recurring care interaction.
 */
object CowHunger {
    /** Founder-chosen pacing: hungry every 5 minutes of real time. */
    const val HUNGER_INTERVAL_MS: Long = 5 * 60 * 1000L

    fun isHungry(lastFedTimestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - lastFedTimestamp >= HUNGER_INTERVAL_MS
}
