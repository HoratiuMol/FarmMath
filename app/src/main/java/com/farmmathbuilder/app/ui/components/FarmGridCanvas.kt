package com.farmmathbuilder.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale as drawScale
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

    // Decorative wandering cow (not tappable, no gameplay effect): a free-floating
    // col/row position animated by its own coroutine, independent of the cell grid.
    // -1f is an "uninitialized" sentinel (valid col/row are always >= 0).
    var cowCol by remember { mutableFloatStateOf(-1f) }
    var cowRow by remember { mutableFloatStateOf(-1f) }
    var cowFacingRight by remember { mutableStateOf(true) }
    var cowAnimMs by remember { mutableFloatStateOf(0f) }
    var prevGridConfigForCow by remember { mutableStateOf<GridConfig?>(null) }

    LaunchedEffect(gridConfig) {
        // expandGrid() shifts every existing cell by +1 col/+1 row to keep the
        // building centered (see FarmRepository.expandGrid) — shift the cow's
        // free-floating position the same way so it doesn't visually teleport
        // relative to the barn/fence when the map grows.
        prevGridConfigForCow?.let { prev ->
            if (gridConfig.cols != prev.cols) {
                val shift = (gridConfig.cols - prev.cols) / 2f
                cowCol += shift
                cowRow += shift
            }
        }
        prevGridConfigForCow = gridConfig

        fun randomWanderTarget(): Offset {
            while (true) {
                val col = Random.nextInt(gridConfig.cols)
                val row = Random.nextInt(gridConfig.rows)
                if (GridMath.isWithinBuildableRadius(col, row, gridConfig.cols, gridConfig.rows, gridConfig.buildableRadius) &&
                    !GridMath.isBuildingCell(col, row, gridConfig.cols, gridConfig.rows)
                ) {
                    return Offset(col.toFloat(), row.toFloat())
                }
            }
        }

        if (cowCol < 0f) {
            val start = randomWanderTarget()
            cowCol = start.x
            cowRow = start.y
        }

        while (true) {
            // "Graze" pause between walks, ticking the anim clock so the tail/legs
            // keep a subtle idle sway instead of freezing.
            var frameMs = withFrameMillis { it }
            val pauseUntilMs = frameMs + Random.nextInt(800, 2400)
            while (frameMs < pauseUntilMs) {
                cowAnimMs = frameMs.toFloat()
                frameMs = withFrameMillis { it }
            }

            val target = randomWanderTarget()
            val startCol = cowCol
            val startRow = cowRow
            val dCol = target.x - startCol
            val dRow = target.y - startRow
            val dist = hypot(dCol, dRow)
            if (dist < 0.05f) continue
            cowFacingRight = dCol >= 0f

            val tilesPerMs = 0.5f / 1000f
            val durationMs = dist / tilesPerMs
            val moveStartMs = frameMs
            while (true) {
                frameMs = withFrameMillis { it }
                cowAnimMs = frameMs.toFloat()
                val t = ((frameMs - moveStartMs) / durationMs).coerceIn(0f, 1f)
                cowCol = startCol + dCol * t
                cowRow = startRow + dRow * t
                if (t >= 1f) break
            }
        }
    }

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

        // Continuous ground plane covering the entire visible canvas (unaffected by
        // scale/pan — drawn once in raw canvas coordinates), so panning/zooming never
        // reveals empty space around the grid: the barn and every crop always sit on
        // green ground. The fence (drawn last, below) is now the only visual cue for
        // where the buildable/playable area actually ends.
        drawRect(color = Color(0xFF9CCC65), size = size)

        for (cell in cells.sortedBy { it.col + it.row }) {
            val baseCx = originOffset.x + GridMath.isoX(cell.col, cell.row, tileW)
            val baseCy = originOffset.y + GridMath.isoY(cell.col, cell.row, tileH)
            val cx = (baseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val cy = (baseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            val w = tileW * scale
            val h = tileH * scale

            val fillColor = when {
                cell.isBuildingCell -> Color(0xFFD84315)
                cell.occupantType == OccupantType.PATH -> Color(0xFFBCAAA4)
                cell.occupantType == OccupantType.WHEAT -> Color(0xFFDCEDC8)
                // Beyond the buildable radius reads as the same green ground as everywhere
                // else now (was a distinct grey) — the fence alone marks the true limit.
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

        // Decorative wandering cow: purely cosmetic, not part of `cells` and never
        // hit-tested (see the tap handler above, which only ever iterates `cells`).
        // Position is driven by the free-floating cowCol/cowRow coroutine above.
        // Drawn after the barn (in front of it always, rather than true depth
        // sorting against the barn mesh — an acceptable simplification for a
        // small roaming decoration) and before the fence, which the cow can
        // never reach anyway since its wander targets are restricted to
        // isWithinBuildableRadius cells, the same interior area the fence encloses.
        if (cowCol >= 0f) {
            val cowBaseCx = originOffset.x + GridMath.isoX(cowCol, cowRow, tileW)
            val cowBaseCy = originOffset.y + GridMath.isoY(cowCol, cowRow, tileH)
            val ccx = (cowBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val ccy = (cowBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            val walkPhase = cowAnimMs / 140f
            val stride = kotlin.math.sin(walkPhase)
            val bob = kotlin.math.abs(stride) * 3f
            val cowUnitScale = (tileW * scale) / 46f
            drawWanderingCow(
                cx = ccx,
                cy = ccy - 18f * cowUnitScale,
                unitScale = cowUnitScale,
                facingRight = cowFacingRight,
                stride = stride,
                bob = bob
            )
        }

        // Decorative fence lining the buildable-area boundary: one precomputed
        // fence module (see FenceMesh) per cell on the grid's own outermost
        // ring (col/row == 0 or cols-1/rows-1), i.e. the true edge of the
        // cols x rows array, not a Chebyshev-distance ring from the building.
        // The building sits off-center for non-square grids (see
        // buildingAnchorCol/Row), so a fixed-radius ring can miss whole sides
        // (e.g. never reach row 0) — hugging the literal grid border instead
        // guarantees the fence always closes off all 4 sides. GridMath.
        // isWithinBuildableRadius excludes this same border ring, so the fence
        // always marks the true limit of where the player can act. Recomputed
        // live from gridConfig so it follows map expansion automatically (no
        // persistence needed). Drawn last, same "draw on top" reasoning as the
        // barn above, in its own pass over the full grid rather than the main
        // per-cell loop.
        run {
            for (col in 0 until gridConfig.cols) {
                for (row in 0 until gridConfig.rows) {
                    if (!GridMath.isOnGridBorder(col, row, gridConfig.cols, gridConfig.rows)) continue
                    // Top/bottom edges vary by column (row pinned at an extreme)
                    // -> use the "runs along columns" variant. Left/right edges
                    // vary by row -> use "runs along rows". Corners satisfy
                    // both; alongColumns is picked consistently for them (no
                    // mitering).
                    val isColumnEdge = row == 0 || row == gridConfig.rows - 1
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

/**
 * The "chibi sticker" cow design (option B of docs/previews/cow-preview.html),
 * ported 1:1 from that preview's `drawCowB` canvas routine: rounded 2D shapes
 * (no mesh/3D), all offsets tuned against that preview's 46px reference tile
 * width, so [unitScale] should be `(tileWidthPx * zoomScale) / 46f` — the same
 * "1 tile-width = 1 unit" convention BarnMesh/FenceMesh triangles use. [cx]/[cy]
 * is the shoulder/back anchor point (not the feet); [stride] and [bob] are the
 * walk-cycle inputs (leg-swing weight and vertical bounce), both continuous
 * functions of the anim clock so the cow keeps a subtle idle sway even while
 * paused between walks.
 */
private fun DrawScope.drawWanderingCow(
    cx: Float,
    cy: Float,
    unitScale: Float,
    facingRight: Boolean,
    stride: Float,
    bob: Float
) {
    val ink = Color(0xFF3A332B)
    val cream = Color(0xFFFFFAF0)
    val snoutColor = Color(0xFFF4B8AB)
    val hornColor = Color(0xFFD8C9A3)

    fun u(v: Float) = v * unitScale

    val anchor = Offset(cx, cy - u(bob))
    val legSwing = u(stride * 6f)

    val draw: DrawScope.() -> Unit = {
        // legs
        drawLine(ink, anchor + Offset(u(-14f), u(14f)), anchor + Offset(u(-14f) + legSwing, u(26f)), strokeWidth = u(3f), cap = StrokeCap.Round)
        drawLine(ink, anchor + Offset(u(10f), u(14f)), anchor + Offset(u(10f) - legSwing, u(26f)), strokeWidth = u(3f), cap = StrokeCap.Round)

        // body
        val bodyCenter = anchor + Offset(u(-4f), u(4f))
        drawOval(cream, topLeft = bodyCenter - Offset(u(26f), u(17f)), size = Size(u(52f), u(34f)))
        drawOval(ink, topLeft = bodyCenter - Offset(u(26f), u(17f)), size = Size(u(52f), u(34f)), style = Stroke(u(2f)))

        // spots
        rotate(degrees = 23f, pivot = anchor + Offset(u(-14f), u(0f))) {
            drawOval(ink, topLeft = anchor + Offset(u(-20f), u(-4.5f)), size = Size(u(12f), u(9f)))
        }
        rotate(degrees = -17f, pivot = anchor + Offset(u(6f), u(9f))) {
            drawOval(ink, topLeft = anchor + Offset(u(1f), u(5.5f)), size = Size(u(10f), u(7f)))
        }

        // head
        val headCenter = anchor + Offset(u(24f), u(-8f))
        drawOval(cream, topLeft = headCenter - Offset(u(17f), u(15f)), size = Size(u(34f), u(30f)))
        drawOval(ink, topLeft = headCenter - Offset(u(17f), u(15f)), size = Size(u(34f), u(30f)), style = Stroke(u(2f)))

        // ears
        rotate(degrees = 34f, pivot = anchor + Offset(u(15f), u(-21f))) {
            drawOval(cream, topLeft = anchor + Offset(u(9f), u(-25f)), size = Size(u(12f), u(8f)))
            drawOval(ink, topLeft = anchor + Offset(u(9f), u(-25f)), size = Size(u(12f), u(8f)), style = Stroke(u(2f)))
        }
        rotate(degrees = -34f, pivot = anchor + Offset(u(30f), u(-22f))) {
            drawOval(cream, topLeft = anchor + Offset(u(24f), u(-26f)), size = Size(u(12f), u(8f)))
            drawOval(ink, topLeft = anchor + Offset(u(24f), u(-26f)), size = Size(u(12f), u(8f)), style = Stroke(u(2f)))
        }

        // snout
        val snoutCenter = anchor + Offset(u(32f), u(-2f))
        drawOval(snoutColor, topLeft = snoutCenter - Offset(u(9f), u(7f)), size = Size(u(18f), u(14f)))
        drawOval(ink, topLeft = snoutCenter - Offset(u(9f), u(7f)), size = Size(u(18f), u(14f)), style = Stroke(u(1.5f)))

        // nostrils
        drawCircle(ink, radius = u(1.3f), center = anchor + Offset(u(29f), u(-2f)))
        drawCircle(ink, radius = u(1.3f), center = anchor + Offset(u(35f), u(-2f)))

        // eyes
        drawCircle(ink, radius = u(2.6f), center = anchor + Offset(u(20f), u(-12f)))
        drawCircle(ink, radius = u(2.6f), center = anchor + Offset(u(30f), u(-13f)))

        // horns
        drawLine(hornColor, anchor + Offset(u(18f), u(-23f)), anchor + Offset(u(15f), u(-28f)), strokeWidth = u(3f), cap = StrokeCap.Round)
        drawLine(hornColor, anchor + Offset(u(27f), u(-24f)), anchor + Offset(u(29f), u(-29f)), strokeWidth = u(3f), cap = StrokeCap.Round)

        // tail
        val tailPath = Path().apply {
            moveTo(anchor.x + u(-28f), anchor.y)
            quadraticTo(
                anchor.x + u(-36f), anchor.y + u(4f + stride * 3f),
                anchor.x + u(-34f), anchor.y + u(14f)
            )
        }
        drawPath(tailPath, color = ink, style = Stroke(width = u(2.5f), cap = StrokeCap.Round))
    }

    if (facingRight) {
        draw()
    } else {
        drawScale(scaleX = -1f, scaleY = 1f, pivot = anchor, draw)
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
