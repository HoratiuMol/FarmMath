package com.farmmathbuilder.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.farmmathbuilder.app.domain.AnimalType

/**
 * A single owned animal (only [AnimalType.COW] exists today, but the table
 * already carries a species field so more can be added without a schema
 * change). Growth stage is derived from [bornAtTimestamp] (see AnimalGrowth),
 * never stored — same kill-safe timestamp pattern as CellEntity's crop growth
 * and the old single-cow PlayerEntity.cowLastFedTimestamp this replaces, now
 * that there can be more than one animal.
 */
@Entity(tableName = "animals")
data class AnimalEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: AnimalType = AnimalType.COW,
    val bornAtTimestamp: Long,
    val lastFedTimestamp: Long
)
