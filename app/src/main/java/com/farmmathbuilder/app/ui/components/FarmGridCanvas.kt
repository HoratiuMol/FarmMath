package com.farmmathbuilder.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.domain.BarnMesh
import com.farmmathbuilder.app.domain.FenceMesh
import com.farmmathbuilder.app.domain.GridConfig
import com.farmmathbuilder.app.domain.GridMath
import com.farmmathbuilder.app.domain.GrowthPhase
import com.farmmathbuilder.app.domain.OccupantType
import com.farmmathbuilder.app.domain.PathType
import com.farmmathbuilder.app.domain.UiCell
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Main isometric 6x8 grid, rendered on a Canvas with 2:1 isometric tile math
 * (R-2): screenX = (col-row)*tileWidth/2, screenY = (col+row)*tileHeight/2.
 * Supports pinch-to-zoom + drag-to-pan (FR-007).
 */
@Composable
fun FarmGridCanvas(
    cells: List<UiCell>,
    gridConfig: GridConfig,
    highlightedCellId: Int?,
    onCellTapped: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val tileWidthDp = 56f
    val tileHeightDp = 30f

    val context = LocalContext.current
    val barnTriangles = remember { BarnMesh.load(context) }
    val fenceVariants = remember { FenceMesh.load(context) }

    val textPaint = remember {
        Paint().apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panDelta, zoom, _ ->
                    // Focal-point-aware zoom: keep the point under the pinch centroid
                    // visually fixed while scale changes, and compose panning at the
                    // same time (standard Compose recipe for detectTransformGestures).
                    val canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val newScale = (scale * zoom).coerceIn(0.5f, 3.5f)
                    val zoomFactor = newScale / scale
                    pan = centroid + panDelta - canvasCenter -
                        (centroid - canvasCenter - pan) * zoomFactor
                    scale = newScale
                }
            }
            .pointerInput(cells, gridConfig) {
                detectTapGestures { tapOffset ->
                    val canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val contentPoint = (tapOffset - canvasCenter - pan) / scale + canvasCenter
                    val tileW = tileWidthDp.dp.toPx()
                    val tileH = tileHeightDp.dp.toPx()
                    val gridOriginOffset = gridOrigin(canvasCenter, tileW, tileH, gridConfig.cols, gridConfig.rows)
                    var hitId: Int? = null
                    for (cell in cells) {
                        val cx = gridOriginOffset.x + GridMath.isoX(cell.col, cell.row, tileW)
                        val cy = gridOriginOffset.y + GridMath.isoY(cell.col, cell.row, tileH)
                        val dx = abs(contentPoint.x - cx)
                        val dy = abs(contentPoint.y - cy)
                        if (dx / (tileW / 2f) + dy / (tileH / 2f) <= 1f) {
                            hitId = cell.id
                            break
                        }
                    }
                    hitId?.let(onCellTapped)
                }
            }
    ) {
        val canvasCenter = Offset(size.width / 2f, size.height / 2f)
        val tileW = tileWidthDp.dp.toPx()
        val tileH = tileHeightDp.dp.toPx()
        val originOffset = gridOrigin(canvasCenter, tileW, tileH, gridConfig.cols, gridConfig.rows)
        val buildingAnchorCol = GridMath.buildingAnchorCol(gridConfig.cols)
        val buildingAnchorRow = GridMath.buildingAnchorRow(gridConfig.rows)

        for (cell in cells.sortedBy { it.col + it.row }) {
            val baseCx = originOffset.x + GridMath.isoX(cell.col, cell.row, tileW)
            val baseCy = originOffset.y + GridMath.isoY(cell.col, cell.row, tileH)
            val cx = (baseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val cy = (baseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            val w = tileW * scale
            val h = tileH * scale

            val fillColor = when {
                cell.isBuildingCell -> Color(0xFFD84315)
                !cell.isWithinBuildableRadius -> Color(0xFF9E9E9E)
                cell.occupantType == OccupantType.PATH -> Color(0xFFBCAAA4)
                cell.occupantType == OccupantType.WHEAT -> Color(0xFFDCEDC8)
                else -> Color(0xFF9CCC65)
            }

            val diamond = Path().apply {
                moveTo(cx, cy - h / 2f)
                lineTo(cx + w / 2f, cy)
                lineTo(cx, cy + h / 2f)
                lineTo(cx - w / 2f, cy)
                close()
            }
            drawPath(diamond, color = fillColor)
            drawPath(diamond, color = Color(0x33000000), style = Stroke(width = 1.5f))

            if (cell.id == highlightedCellId) {
                drawPath(diamond, color = Color(0xFFFFEE58), style = Stroke(width = 4f * scale))
            }

            when (cell.occupantType) {
                OccupantType.BUILDING -> {
                    // Ground diamond/border for this building cell is already drawn
                    // above (same as every other cell). The actual building shape is
                    // drawn once, after the whole loop finishes, so it can never be
                    // occluded by a neighboring building cell's diamond fill — see the
                    // z-order fix below (was: drawn here mid-loop, which caused later
                    // cells in the 2x2 block to paint over most of it).
                }
                OccupantType.WHEAT -> {
                    drawWheatTile(cx, cy, w, h, cell.growthPhase, cell.growthProgress, cell.id)
                }
                OccupantType.PATH -> {
                    drawPathPiece(cx, cy, w, h, cell.pathType, cell.pathRotationDegrees)
                }
                OccupantType.EMPTY -> {
                    if (cell.isWithinBuildableRadius) {
                        textPaint.textSize = 14f * scale
                        textPaint.color = android.graphics.Color.argb(90, 0, 0, 0)
                        drawContext.canvas.nativeCanvas.drawText("+", cx, cy + 5f * scale, textPaint)
                        textPaint.color = android.graphics.Color.BLACK
                    }
                }
            }
        }

        // Draw the central 2x2 farm building last, on top of every tile in the
        // grid, so it's never occluded by a neighboring building cell's diamond
        // fill (the bug: drawing it mid-loop on the "primary" cell meant the
        // other 3 building cells, which sort later along col+row, painted their
        // ground diamonds over most of the already-drawn house). Position
        // computed the same way the old inline code did, centered on the 2x2
        // footprint. The building itself is now a real parsed/flat-shaded 3D
        // barn mesh (precomputed once in BarnMesh, not per-frame): each
        // triangle's normalized (tile-relative) offsets are scaled by tileW
        // (uniformly for both axes — see BarnMesh doc for why) and the current
        // scale, then filled in the mesh's precomputed back-to-front order.
        run {
            val centerCol = buildingAnchorCol + 0.5f
            val centerRow = buildingAnchorRow + 0.5f
            val bBaseCx = originOffset.x + GridMath.isoX(centerCol, centerRow, tileW)
            val bBaseCy = originOffset.y + GridMath.isoY(centerCol, centerRow, tileH)
            val bx = (bBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val by = (bBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            for (tri in barnTriangles) {
                val p0x = bx + tri.normX0 * tileW * scale
                val p0y = by + tri.normY0 * tileW * scale
                val p1x = bx + tri.normX1 * tileW * scale
                val p1y = by + tri.normY1 * tileW * scale
                val p2x = bx + tri.normX2 * tileW * scale
                val p2y = by + tri.normY2 * tileW * scale
                val triPath = Path().apply {
                    moveTo(p0x, p0y)
                    lineTo(p1x, p1y)
                    lineTo(p2x, p2y)
                    close()
                }
                drawPath(triPath, color = tri.color)
            }
        }

        // Decorative fence lining the buildable-area boundary: one precomputed
        // fence module (see FenceMesh) per cell on the ring *just outside* the
        // buildable Chebyshev-disk area (radius + 1 — the nearest locked/
        // unreachable ring), so the fence marks the true limit of where the
        // player can act instead of sitting on a tappable/buildable cell
        // (distance == radius is still inside isWithinBuildableRadius's
        // <= comparison). Recomputed live from gridConfig so it follows map
        // expansion automatically (no persistence needed). Drawn last, same
        // "draw on top" reasoning as the barn above, in its own pass over the
        // full grid rather than the main per-cell loop.
        run {
            val fenceRingDistance = gridConfig.buildableRadius + 1
            for (col in 0 until gridConfig.cols) {
                for (row in 0 until gridConfig.rows) {
                    if (GridMath.distanceFromBuilding(col, row, gridConfig.cols, gridConfig.rows) != fenceRingDistance) continue
                    // Top/bottom edges of the ring vary by column (row pinned at
                    // an extreme) -> use the "runs along columns" variant.
                    // Left/right edges vary by row -> use "runs along rows".
                    // Corners satisfy both; alongColumns is picked consistently
                    // for them (no mitering).
                    val isColumnEdge = row == buildingAnchorRow - fenceRingDistance || row == buildingAnchorRow + fenceRingDistance
                    val variant = if (isColumnEdge) fenceVariants.alongColumns else fenceVariants.alongRows
                    val fBaseCx = originOffset.x + GridMath.isoX(col, row, tileW)
                    val fBaseCy = originOffset.y + GridMath.isoY(col, row, tileH)
                    val fx = (fBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
                    val fy = (fBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
                    for (tri in variant) {
                        val p0x = fx + tri.normX0 * tileW * scale
                        val p0y = fy + tri.normY0 * tileW * scale
                        val p1x = fx + tri.normX1 * tileW * scale
                        val p1y = fy + tri.normY1 * tileW * scale
                        val p2x = fx + tri.normX2 * tileW * scale
                        val p2y = fy + tri.normY2 * tileW * scale
                        val fenceTriPath = Path().apply {
                            moveTo(p0x, p0y)
                            lineTo(p1x, p1y)
                            lineTo(p2x, p2y)
                            close()
                        }
                        drawPath(fenceTriPath, color = tri.color)
                    }
                }
            }
        }
    }
}

private fun gridOrigin(canvasCenter: Offset, tileW: Float, tileH: Float, cols: Int, rows: Int): Offset {
    // Center the current cols x rows grid's bounding box on the canvas.
    val minX = GridMath.isoX(0, rows - 1, tileW)
    val maxX = GridMath.isoX(cols - 1, 0, tileW)
    val minY = GridMath.isoY(0, 0, tileH)
    val maxY = GridMath.isoY(cols - 1, rows - 1, tileH)
    val gridCenterX = (minX + maxX) / 2f
    val gridCenterY = (minY + maxY) / 2f
    return Offset(canvasCenter.x - gridCenterX, canvasCenter.y - gridCenterY)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPathPiece(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    pathType: PathType?,
    rotationDegrees: Int
) {
    val brown = Color(0xFF6D4C41)
    val thickness = min(w, h) * 0.18f
    when (pathType) {
        PathType.STRAIGHT -> {
            drawLine(brown, Offset(cx - w / 2.2f, cy), Offset(cx + w / 2.2f, cy), strokeWidth = thickness)
        }
        PathType.CORNER -> {
            drawLine(brown, Offset(cx - w / 2.2f, cy), Offset(cx, cy), strokeWidth = thickness)
            drawLine(brown, Offset(cx, cy), Offset(cx, cy - h / 2.2f), strokeWidth = thickness)
        }
        PathType.T_JUNCTION -> {
            drawLine(brown, Offset(cx - w / 2.2f, cy), Offset(cx + w / 2.2f, cy), strokeWidth = thickness)
            drawLine(brown, Offset(cx, cy), Offset(cx, cy - h / 2.2f), strokeWidth = thickness)
        }
        null -> {}
    }
}

/**
 * Deterministically scatters [count] points inside the diamond tile footprint
 * (centered on the origin, local coordinates), seeded by [seed] so the layout
 * is stable across recompositions/frames as long as the seed doesn't change.
 * Uses rejection sampling against the diamond's |x|/halfW + |y|/halfH <= margin
 * bound (same shape as the tile diamond, [margin] < 1 keeps points off the edges).
 */
private fun scatterPointsInDiamond(seed: Int, count: Int, w: Float, h: Float, margin: Float = 0.85f): List<Offset> {
    val rnd = Random(seed)
    val halfW = w / 2f
    val halfH = h / 2f
    val points = ArrayList<Offset>(count)
    var attempts = 0
    val maxAttempts = count * 25
    while (points.size < count && attempts < maxAttempts) {
        attempts++
        val nx = rnd.nextFloat() * 2f - 1f
        val ny = rnd.nextFloat() * 2f - 1f
        if (abs(nx) + abs(ny) <= margin) {
            points.add(Offset(nx * halfW, ny * halfH))
        }
    }
    return points
}

/**
 * Renders a wheat cell as a full-tile procedural field patch instead of a
 * single centered emoji: layered soil/seed/blade/wheat strokes that fill the
 * whole diamond footprint, one visual treatment per [GrowthPhase]. Everything
 * is deterministic per-cell — all randomness is seeded from [seed] (the cell
 * id, mixed with small per-layer offsets) via a fresh `Random` recreated each
 * call, so the scattered layout doesn't reshuffle on every per-second tick
 * recomposition while the phase is unchanged.
 */
private fun DrawScope.drawWheatTile(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    phase: GrowthPhase,
    progress: Float,
    seed: Int
) {
    // Scale strokes/dots relative to the base tile width so they track zoom
    // the same way the rest of the tile (w/h already have `scale` applied).
    val scaleFactor = (w / 56f).coerceAtLeast(0.05f)
    val furrowDir = run {
        val len = hypot(w, h)
        if (len == 0f) Offset(1f, 0f) else Offset(w / len, h / len)
    }
    val soilDark = Color(0xFF6D4C41)

    fun drawFurrows(count: Int, alpha: Float, minLocalY: Float) {
        val pts = scatterPointsInDiamond(seed * 31 + phase.ordinal * 7 + 101, count, w, h, margin = 0.75f)
        val lenHalf = furrowDir * (min(w, h) * 0.18f)
        for (p in pts) {
            if (p.y < minLocalY) continue
            val center = Offset(cx + p.x, cy + p.y)
            drawLine(
                color = soilDark.copy(alpha = alpha),
                start = center - lenHalf,
                end = center + lenHalf,
                strokeWidth = 1.6f * scaleFactor
            )
        }
    }

    fun drawSeedDots(count: Int) {
        val pts = scatterPointsInDiamond(seed * 31 + phase.ordinal * 7 + 202, count, w, h, margin = 0.7f)
        for (p in pts) {
            drawCircle(
                color = Color(0xFF3E2723),
                radius = 1.4f * scaleFactor,
                center = Offset(cx + p.x, cy + p.y)
            )
        }
    }

    fun drawBlades(count: Int, seedOffset: Int, heightFactor: Float, colors: List<Color>) {
        val pts = scatterPointsInDiamond(seed * 31 + phase.ordinal * 7 + seedOffset, count, w, h, margin = 0.82f)
        val rnd = Random(seed * 31 + phase.ordinal * 7 + seedOffset + 1)
        for (p in pts) {
            val base = Offset(cx + p.x, cy + p.y)
            val bladeH = min(w, h) * (0.22f + rnd.nextFloat() * 0.14f) * heightFactor
            val tilt = (rnd.nextFloat() - 0.5f) * bladeH * 0.5f
            val tip = Offset(base.x + tilt, base.y - bladeH)
            val color = colors[rnd.nextInt(colors.size)]
            drawLine(
                color = color,
                start = base,
                end = tip,
                strokeWidth = 1.8f * scaleFactor,
                cap = StrokeCap.Round
            )
        }
    }

    when (phase) {
        GrowthPhase.SEED -> {
            // Bare/tilled soil: short furrow lines following the iso grid
            // direction, plus a scatter of tiny seed dots.
            drawFurrows(count = 6, alpha = 0.85f, minLocalY = -h)
            drawSeedDots(count = 5)
        }
        GrowthPhase.SPROUT -> {
            // Soil still visible near the base, small green blade scatter on top.
            drawFurrows(count = 3, alpha = 0.55f, minLocalY = h * 0.05f)
            val heightFactor = 0.55f + 0.25f * progress
            drawBlades(
                count = 10,
                seedOffset = 303,
                heightFactor = heightFactor,
                colors = listOf(Color(0xFF7CB342), Color(0xFF689F38), Color(0xFF558B2F))
            )
        }
        GrowthPhase.PLANT -> {
            // Soil mostly covered; denser, taller, non-uniform green clusters.
            drawFurrows(count = 2, alpha = 0.3f, minLocalY = h * 0.3f)
            val heightFactor = 0.8f + 0.25f * progress
            drawBlades(
                count = 14,
                seedOffset = 404,
                heightFactor = heightFactor,
                colors = listOf(Color(0xFF558B2F), Color(0xFF33691E), Color(0xFF689F38))
            )
        }
        GrowthPhase.MATURE -> {
            // Subtle light-direction shading (lighter near the top of the
            // diamond, darker toward the base) to fake a little dimensionality.
            val diamond = Path().apply {
                moveTo(cx, cy - h / 2f)
                lineTo(cx + w / 2f, cy)
                lineTo(cx, cy + h / 2f)
                lineTo(cx - w / 2f, cy)
                close()
            }
            drawPath(
                diamond,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x40FFFFFF), Color(0x00FFFFFF), Color(0x33000000)),
                    startY = cy - h / 2f,
                    endY = cy + h / 2f
                )
            )
            val goldColors = listOf(Color(0xFFFFC107), Color(0xFFFFB300), Color(0xFFFFD54F))
            drawBlades(
                count = 14,
                seedOffset = 505,
                heightFactor = 1f,
                colors = goldColors
            )
            // A few darker accent strokes so the field doesn't read as a flat
            // texture stamp.
            val accentPts = scatterPointsInDiamond(seed * 31 + phase.ordinal * 7 + 606, 3, w, h, margin = 0.7f)
            for (p in accentPts) {
                val base = Offset(cx + p.x, cy + p.y)
                val bladeH = min(w, h) * 0.26f
                drawLine(
                    color = Color(0xFF8D6E63),
                    start = base,
                    end = Offset(base.x, base.y - bladeH),
                    strokeWidth = 1.6f * scaleFactor,
                    cap = StrokeCap.Round
                )
            }
        }
        GrowthPhase.NONE -> {
            // Wheat cells should never be NONE, but keep the branch for
            // exhaustiveness — nothing to draw.
        }
    }
}
