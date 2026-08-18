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
    val lastFedTimestamp: Long,
    /** Real-world creation time, used only for lifespan/death (see AnimalLifespan) —
     * kept separate from [bornAtTimestamp] because that field is deliberately
     * backdated by CALF_GROWTH_DURATION_MS when a cow is bought or seeded so she
     * starts as an adult; reusing it for lifespan would make every purchased cow
     * die instantly. */
    val spawnedAtTimestamp: Long = bornAtTimestamp
)
