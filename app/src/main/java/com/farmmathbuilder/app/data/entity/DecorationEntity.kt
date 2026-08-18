package com.farmmathbuilder.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.farmmathbuilder.app.domain.DecorationSide
import com.farmmathbuilder.app.domain.DecorationType

/**
 * One player-placed map decoration (founder request 2026-08-18: a "geographic
 * features" shop, only RIVER exists today). Position is stored relative — which
 * border edge ([side]) and how far along it ([alongFraction], 0..1) — rather
 * than as absolute col/row, so FarmGridCanvas can always re-project it onto the
 * grid's *current* cols/rows: it stays outside the fence and in the same
 * relative spot (e.g. "a third of the way along the top edge") through every
 * map expansion, with no migration needed on expandGrid.
 */
@Entity(tableName = "decorations")
data class DecorationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: DecorationType,
    val side: DecorationSide,
    val alongFraction: Float
)
