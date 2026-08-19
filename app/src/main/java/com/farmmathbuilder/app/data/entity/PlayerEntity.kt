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
    val cowFeedsSinceBreedingRoll: Int = 0,
    /** A currency earned only by solving math — kept deliberately separate from
     * wheatCurrency (which comes from farming) so a child can see matemáticas
     * pay off in something visibly its own, not folded into the farm economy.
     * +1 per correct answer in both the casual exercise flow and the 10-in-a-
     * row Challenge, plus a bonus on full Challenge completion. Not spendable
     * yet — see FarmRepository for where it's granted. */
    val mathStars: Int = 0,
    /** True once today's daily math mission (solve
     * [com.farmmathbuilder.app.data.repository.FarmRepository.DAILY_MISSION_TARGET]
     * problems) has paid out its bonus — resets to false every daily reset
     * alongside exercisesSolvedToday, same as every other "today" field. */
    val dailyMissionClaimed: Boolean = false
)
