package com.farmmathbuilder.app.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Grid size/radius bundle threaded through GridMath/FarmGridCanvas/ViewModel/UiState
 * so the grid can grow at runtime (map expansion) instead of being fixed forever.
 */
data class GridConfig(val cols: Int, val rows: Int, val buildableRadius: Int) {
    companion object {
        val BASE = GridConfig(GridMath.BASE_COLS, GridMath.BASE_ROWS, GridMath.BASE_RADIUS)
    }
}

/**
 * Square grid, rendered with a standard 2:1 isometric projection:
 * screenX = (col - row) * tileWidth/2, screenY = (col + row) * tileHeight/2.
 * Starting size is 6 columns x 8 rows (R-2); the grid can expand at runtime
 * (see FarmRepository.expandGrid), so cols/rows/radius are always passed in
 * from the player's current grid state rather than hardcoded.
 */
object GridMath {
    /** Starting grid size / buildable radius — named constants, not runtime truth. */
    const val BASE_COLS = 6
    const val BASE_ROWS = 8
    const val BASE_RADIUS = 3

    fun cellId(col: Int, row: Int, cols: Int): Int = row * cols + col

    /** Top-left anchor of the central 2x2 "Farm Building" footprint. */
    fun buildingAnchorCol(cols: Int): Int = cols / 2 - 1
    fun buildingAnchorRow(rows: Int): Int = rows / 2 - 1

    /** BR-002: the central building occupies a 2x2 block of 4 permanently non-editable cells. */
    fun isBuildingCell(col: Int, row: Int, cols: Int, rows: Int): Boolean {
        val bc = buildingAnchorCol(cols)
        val br = buildingAnchorRow(rows)
        return (col == bc || col == bc + 1) && (row == br || row == br + 1)
    }

    /** Chebyshev distance from the building anchor — used to decide "locked vs available". */
    fun distanceFromBuilding(col: Int, row: Int, cols: Int, rows: Int): Int {
        val bc = buildingAnchorCol(cols)
        val br = buildingAnchorRow(rows)
        return max(abs(col - bc), abs(row - br))
    }

    fun isWithinBuildableRadius(col: Int, row: Int, cols: Int, rows: Int, radius: Int): Boolean =
        distanceFromBuilding(col, row, cols, rows) <= radius

    /** Isometric screen-space projection (unscaled, tile-unit space). */
    fun isoX(col: Number, row: Number, tileWidth: Float): Float =
        (col.toFloat() - row.toFloat()) * (tileWidth / 2f)

    fun isoY(col: Number, row: Number, tileHeight: Float): Float =
        (col.toFloat() + row.toFloat()) * (tileHeight / 2f)
}
