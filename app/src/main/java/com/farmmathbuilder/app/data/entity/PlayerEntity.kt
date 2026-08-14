package com.farmmathbuilder.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.farmmathbuilder.app.domain.AgeBand

/** Single-row singleton table (A-5: one local save profile per device install). */
@Entity(tableName = "player")
data class PlayerEntity(
    @PrimaryKey val id: Int = 1,
    val wheatCurrency: Int = 0,
    val freeFieldsUsedToday: Int = 0,
    val extraFieldsEarnedToday: Int = 0,
    val extraFieldsUsedToday: Int = 0,
    val lastDailyResetTimestamp: Long = 0L,
    val ageBand: AgeBand? = null,
    val exercisesSolvedToday: Int = 0,
    val currentStreak: Int = 0,
    val pathTypesUnlocked: Int = 1,
    val fieldsCompletedTotal: Int = 0,
    /** Map expansion (founder request): grid can grow beyond the starting 6x8. */
    val gridCols: Int = 6,
    val gridRows: Int = 8,
    val gridExpansionLevel: Int = 0,
    val buildableRadius: Int = 3,
    /** Top-left anchor of the Farm Building's 2x2 footprint; null = default grid
     * center (see GridMath.defaultBuildingAnchorCol/Row). Set once the player uses
     * "move barn" (FarmRepository.moveBuilding). */
    val buildingAnchorCol: Int? = null,
    val buildingAnchorRow: Int? = null,
    /** Wheat-only harvest count — carrot's unlock gate specifically watches this,
     * not [fieldsCompletedTotal] (which mixes in carrot harvests once unlocked). */
    val wheatHarvestedTotal: Int = 0,
    /** Timestamp-based like every other timer in this app (GrowthCalculator,
     * daily reset) — never a decrementing counter, so it survives app kill.
     * See [com.farmmathbuilder.app.domain.CowHunger]. */
    val cowLastFedTimestamp: Long = 0L,
    /** Harvested-carrot stockpile: carrot's whole purpose (unlike wheat, which
     * pays wheatCurrency), consumed 1-at-a-time to feed the cow. See
     * FarmRepository.harvest/feedCow. */
    val carrotInventory: Int = 0
)
