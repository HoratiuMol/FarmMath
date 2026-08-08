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
    val onboardingCompleted: Boolean = false,
    /** R-9: tracks whether the tutorial's shortened-timer first field has been planted yet. */
    val tutorialFirstFieldPlanted: Boolean = false,
    val pathTypesUnlocked: Int = 1,
    val fieldsCompletedTotal: Int = 0,
    /** Map expansion (founder request): grid can grow beyond the starting 6x8. */
    val gridCols: Int = 6,
    val gridRows: Int = 8,
    val gridExpansionLevel: Int = 0,
    val buildableRadius: Int = 3
)
