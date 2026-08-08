package com.farmmathbuilder.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.farmmathbuilder.app.domain.OccupantType
import com.farmmathbuilder.app.domain.PathType

/**
 * Cell.id / occupantType / growthPhase / plantedAtTimestamp per BRD Section 7.
 * growthPhase is NOT stored as a static enum truth — it is recomputed from
 * plantedAtTimestamp + growthDurationMs on every read (see GrowthCalculator).
 * We persist plantedAtTimestamp (0 = not planted) and growthDurationMs (the
 * duration that applied at plant time, so a tutorial-shortened field keeps its
 * own duration even though later fields use the real 10-minute timer).
 */
@Entity(tableName = "cells")
data class CellEntity(
    @PrimaryKey val id: Int,
    val col: Int,
    val row: Int,
    val occupantType: OccupantType = OccupantType.EMPTY,
    val plantedAtTimestamp: Long = 0L,
    val growthDurationMs: Long = 0L,
    val pathType: PathType? = null,
    val pathRotationDegrees: Int = 0,
    /** Which daily-slot type (free vs extra) this planting consumed, for cancel-refund (R-4). */
    val consumedSlotType: String? = null // "FREE" | "EXTRA" | null
)
