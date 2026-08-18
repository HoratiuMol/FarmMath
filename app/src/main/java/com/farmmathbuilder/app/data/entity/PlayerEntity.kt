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
    /** Harvested-carrot stockpile: carrot's whole purpose (unlike wheat, which
     * pays wheatCurrency), consumed 1-at-a-time to feed an animal. See
     * FarmRepository.harvest/feedAnimal. */
    val carrotInventory: Int = 0,
    /** Counts feed actions (any animal) since the last breeding roll — every
     * 2nd feed rolls one breeding check and resets to 0. See
     * FarmRepository.feedAnimal / the founder's "al alimentar dos vacas" rule. */
    val cowFeedsSinceBreedingRoll: Int = 0
)
