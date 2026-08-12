package com.farmmathbuilder.app.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Grid size/radius bundle threaded through GridMath/FarmGridCanvas/ViewModel/UiState
 * so the grid can grow at runtime (map expansion) instead of being fixed forever.
 * buildingAnchorCol/Row is the top-left cell of the Farm Building's 2x2 footprint —
 * defaults to the grid center (see [GridMath.defaultBuildingAnchorCol]/[Row]) but is
 * player-repositionable (see FarmRepository.moveBuilding), so it's carried as plain
 * state here rather than always recomputed from cols/rows.
 */
data class GridConfig(
    val cols: Int,
    val rows: Int,
    val buildableRadius: Int,
    val buildingAnchorCol: Int,
    val buildingAnchorRow: Int
) {
    companion object {
        val BASE = GridConfig(
            GridMath.BASE_COLS,
            GridMath.BASE_ROWS,
            GridMath.BASE_RADIUS,
            GridMath.defaultBuildingAnchorCol(GridMath.BASE_COLS),
            GridMath.defaultBuildingAnchorRow(GridMath.BASE_ROWS)
        )
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

    /** Default top-left anchor of the central 2x2 "Farm Building" footprint (grid
     * center) — used to seed a new save and as the fallback when the player hasn't
     * repositioned the building yet. The building's *actual* current anchor is
     * carried explicitly on [GridConfig] (player-repositionable, see
     * FarmRepository.moveBuilding), so callers with a GridConfig should use its
     * buildingAnchorCol/Row instead of recomputing the default here. */
    fun defaultBuildingAnchorCol(cols: Int): Int = cols / 2 - 1
    fun defaultBuildingAnchorRow(rows: Int): Int = rows / 2 - 1

    /** BR-002: the building occupies a 2x2 block of 4 permanently non-editable cells,
     * anchored at (anchorCol, anchorRow) — its top-left cell. */
    fun isBuildingCell(col: Int, row: Int, anchorCol: Int, anchorRow: Int): Boolean =
        (col == anchorCol || col == anchorCol + 1) && (row == anchorRow || row == anchorRow + 1)

    /** Chebyshev distance from the building anchor — used to decide "locked vs available". */
    fun distanceFromBuilding(col: Int, row: Int, anchorCol: Int, anchorRow: Int): Int =
        max(abs(col - anchorCol), abs(row - anchorRow))

    /** True for the outermost ring of the grid array — this is exactly where the
     * boundary fence is drawn (see FarmGridCanvas), so it's never buildable. */
    fun isOnGridBorder(col: Int, row: Int, cols: Int, rows: Int): Boolean =
        col == 0 || row == 0 || col == cols - 1 || row == rows - 1

    /** Buildable = within radius of the building AND not on the fenced-off border
     * ring, so the playable area is always contained by the fence on all 4 sides
     * regardless of how asymmetric cols/rows/building position are. */
    fun isWithinBuildableRadius(col: Int, row: Int, cols: Int, rows: Int, radius: Int, anchorCol: Int, anchorRow: Int): Boolean =
        distanceFromBuilding(col, row, anchorCol, anchorRow) <= radius && !isOnGridBorder(col, row, cols, rows)

    /** True if a 2x2 building footprint anchored at (anchorCol, anchorRow) would fit
     * entirely inside the buildable ring (never touching/overlapping the fenced-off
     * border) — the placement-validity check for FarmRepository.moveBuilding. */
    fun isValidBuildingAnchor(anchorCol: Int, anchorRow: Int, cols: Int, rows: Int): Boolean =
        anchorCol >= 1 && anchorCol <= cols - 3 && anchorRow >= 1 && anchorRow <= rows - 3

    /** Isometric screen-space projection (unscaled, tile-unit space). */
    fun isoX(col: Number, row: Number, tileWidth: Float): Float =
        (col.toFloat() - row.toFloat()) * (tileWidth / 2f)

    fun isoY(col: Number, row: Number, tileHeight: Float): Float =
        (col.toFloat() + row.toFloat()) * (tileHeight / 2f)
}
