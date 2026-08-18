package com.farmmathbuilder.app.domain

/**
 * Grid size bundle threaded through GridMath/FarmGridCanvas/ViewModel/UiState
 * so the grid can grow at runtime (map expansion) instead of being fixed forever.
 */
data class GridConfig(
    val cols: Int,
    val rows: Int
) {
    companion object {
        val BASE = GridConfig(GridMath.BASE_COLS, GridMath.BASE_ROWS)
    }
}

/**
 * Square grid, rendered with a standard 2:1 isometric projection:
 * screenX = (col - row) * tileWidth/2, screenY = (col + row) * tileHeight/2.
 * Starting size is 6 columns x 8 rows; the grid can expand at runtime (see
 * FarmRepository.expandGrid), so cols/rows are always passed in from the
 * player's current grid state rather than hardcoded.
 */
object GridMath {
    /** Starting grid size — named constants, not runtime truth. */
    const val BASE_COLS = 6
    const val BASE_ROWS = 8

    fun cellId(col: Int, row: Int, cols: Int): Int = row * cols + col

    /** True for the outermost ring of the grid array — this is exactly where the
     * boundary fence is drawn (see FarmGridCanvas), so it's never buildable. */
    fun isOnGridBorder(col: Int, row: Int, cols: Int, rows: Int): Boolean =
        col == 0 || row == 0 || col == cols - 1 || row == rows - 1

    /** Buildable = every cell except the fenced-off border ring. The Farm Building
     * now lives permanently outside the grid, attached to its fence (see
     * FarmGridCanvas's flat-barn drawing) — there's no in-grid footprint to carve
     * out any more, so the fence alone marks the limit of where the player can act. */
    fun isBuildable(col: Int, row: Int, cols: Int, rows: Int): Boolean =
        !isOnGridBorder(col, row, cols, rows)

    /** Isometric screen-space projection (unscaled, tile-unit space). */
    fun isoX(col: Number, row: Number, tileWidth: Float): Float =
        (col.toFloat() - row.toFloat()) * (tileWidth / 2f)

    fun isoY(col: Number, row: Number, tileHeight: Float): Float =
        (col.toFloat() + row.toFloat()) * (tileHeight / 2f)
}
