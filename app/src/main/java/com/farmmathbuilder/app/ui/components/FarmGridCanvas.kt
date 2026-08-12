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
import com.farmmathbuilder.app.domain.isCrop
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
    modifier: Modifier = Modifier,
    isRepositioningBuilding: Boolean = false,
    onRepositionTarget: (col: Int, row: Int) -> Unit = { _, _ -> }
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
                if (GridMath.isWithinBuildableRadius(col, row, gridConfig.cols, gridConfig.rows, gridConfig.buildableRadius, gridConfig.buildingAnchorCol, gridConfig.buildingAnchorRow) &&
                    !GridMath.isBuildingCell(col, row, gridConfig.buildingAnchorCol, gridConfig.buildingAnchorRow)
                ) {
                    return Offset(col.toFloat(), row.toFloat())
                }
            }
        }

        // The barn is the only "solid" obstacle the free-floating cow can walk
        // through: randomWanderTarget only excludes the building's own 2x2
        // cells, but a *straight-line* walk between two valid targets on
        // opposite sides of the barn still cuts across it (the buildable ring
        // minus the building is a non-convex region). Model the barn as an
        // axis-aligned rectangle in tile-space, expanded a bit past its 2x2
        // cell footprint (+0.5 for the cell-center-to-edge half, +0.35 buffer
        // for the mesh's roof/porch overhang and the cow sprite's own size),
        // and route the walk around it instead of through it whenever a leg
        // would cross it.
        val margin = 0.5f + 0.35f
        val rectMinCol = gridConfig.buildingAnchorCol.toFloat() - margin
        val rectMaxCol = gridConfig.buildingAnchorCol.toFloat() + 1f + margin
        val rectMinRow = gridConfig.buildingAnchorRow.toFloat() - margin
        val rectMaxRow = gridConfig.buildingAnchorRow.toFloat() + 1f + margin

        // Liang-Barsky segment/AABB clip test: true if segment (x0,y0)-(x1,y1)
        // crosses or touches the rectangle's interior.
        fun segmentHitsRect(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
            var t0 = 0f
            var t1 = 1f
            val dx = x1 - x0
            val dy = y1 - y0
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(x0 - rectMinCol, rectMaxCol - x0, y0 - rectMinRow, rectMaxRow - y0)
            for (i in 0 until 4) {
                if (p[i] == 0f) {
                    if (q[i] < 0f) return false
                } else {
                    val r = q[i] / p[i]
                    if (p[i] < 0f) {
                        if (r > t1) return false
                        if (r > t0) t0 = r
                    } else {
                        if (r < t0) return false
                        if (r < t1) t1 = r
                    }
                }
            }
            return true
        }

        // Builds the sequence of waypoints (ending at [end]) the cow should
        // walk through to get from [start] to [end] without crossing the
        // barn rectangle. A direct walk needs none; a blocked one is routed
        // through whichever corner of the (slightly outset, so it never
        // re-touches the rectangle) obstacle box keeps both legs clear and
        // minimizes total distance.
        fun planPath(start: Offset, end: Offset): List<Offset> {
            if (!segmentHitsRect(start.x, start.y, end.x, end.y)) return listOf(end)
            val cornerEps = 0.05f
            val corners = listOf(
                Offset(rectMinCol - cornerEps, rectMinRow - cornerEps),
                Offset(rectMaxCol + cornerEps, rectMinRow - cornerEps),
                Offset(rectMinCol - cornerEps, rectMaxRow + cornerEps),
                Offset(rectMaxCol + cornerEps, rectMaxRow + cornerEps)
            )
            var best: Offset? = null
            var bestDist = Float.POSITIVE_INFINITY
            for (corner in corners) {
                val leg1Clear = !segmentHitsRect(start.x, start.y, corner.x, corner.y)
                val leg2Clear = !segmentHitsRect(corner.x, corner.y, end.x, end.y)
                if (leg1Clear && leg2Clear) {
                    val d = hypot(corner.x - start.x, corner.y - start.y) + hypot(end.x - corner.x, end.y - corner.y)
                    if (d < bestDist) {
                        bestDist = d
                        best = corner
                    }
                }
            }
            // No single corner clears both legs (can happen if start/end are
            // deep in a concave pocket) — falling back to a direct walk is a
            // rare, harmless simplification rather than a full navmesh.
            return best?.let { listOf(it, end) } ?: listOf(end)
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
            val waypoints = planPath(Offset(cowCol, cowRow), target)
            val tilesPerMs = 0.5f / 1000f

            for (waypoint in waypoints) {
                val startCol = cowCol
                val startRow = cowRow
                val dCol = waypoint.x - startCol
                val dRow = waypoint.y - startRow
                val dist = hypot(dCol, dRow)
                if (dist < 0.05f) continue
                cowFacingRight = dCol >= 0f

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
            .pointerInput(cells, gridConfig, isRepositioningBuilding) {
                detectTapGestures { tapOffset ->
                    val canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val contentPoint = (tapOffset - canvasCenter - pan) / scale + canvasCenter
                    val tileW = tileWidthDp.dp.toPx()
                    val tileH = tileHeightDp.dp.toPx()
                    val gridOriginOffset = gridOrigin(canvasCenter, tileW, tileH, gridConfig.cols, gridConfig.rows)
                    var hitCell: UiCell? = null
                    for (cell in cells) {
                        val cx = gridOriginOffset.x + GridMath.isoX(cell.col, cell.row, tileW)
                        val cy = gridOriginOffset.y + GridMath.isoY(cell.col, cell.row, tileH)
                        val dx = abs(contentPoint.x - cx)
                        val dy = abs(contentPoint.y - cy)
                        if (dx / (tileW / 2f) + dy / (tileH / 2f) <= 1f) {
                            hitCell = cell
                            break
                        }
                    }
                    if (isRepositioningBuilding) {
                        hitCell?.let { onRepositionTarget(it.col, it.row) }
                    } else {
                        hitCell?.let { onCellTapped(it.id) }
                    }
                }
            }
    ) {
        val canvasCenter = Offset(size.width / 2f, size.height / 2f)
        val tileW = tileWidthDp.dp.toPx()
        val tileH = tileHeightDp.dp.toPx()
        val originOffset = gridOrigin(canvasCenter, tileW, tileH, gridConfig.cols, gridConfig.rows)
        val buildingAnchorCol = gridConfig.buildingAnchorCol
        val buildingAnchorRow = gridConfig.buildingAnchorRow

        // "Move barn" placement affordance: a candidate anchor is valid if its 2x2
        // footprint fits inside the buildable ring and every one of those 4 cells is
        // either empty or already part of the *current* barn (tapping its own spot is
        // a harmless no-op). Mirrors FarmRepository.isValidBuildingTarget's rule, just
        // computed locally from the already-loaded `cells` for instant visual feedback.
        fun isValidRepositionAnchor(col: Int, row: Int): Boolean {
            if (!GridMath.isValidBuildingAnchor(col, row, gridConfig.cols, gridConfig.rows)) return false
            val targets = listOf(col to row, col + 1 to row, col to row + 1, col + 1 to row + 1)
            return targets.all { (c, r) ->
                val target = cells.find { it.col == c && it.row == r }
                target != null && (target.occupantType == OccupantType.EMPTY || target.isBuildingCell)
            }
        }

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
                // A warm soil brown (rather than the old bright red-orange) reads as
                // packed dirt around the barn's own wood/roof palette instead of a
                // jarring "sticker" patch — part of grounding the barn visually.
                cell.isBuildingCell -> Color(0xFF9C7B4A)
                cell.occupantType == OccupantType.PATH -> Color(0xFFBCAAA4)
                cell.occupantType.isCrop() -> Color(0xFFDCEDC8)
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

            // "Move barn" mode: mark every cell that's a valid new anchor (top-left
            // corner of the relocated 2x2 footprint) so the player knows where a tap
            // will land the building, instead of discovering valid/invalid spots by
            // trial and error.
            if (isRepositioningBuilding && isValidRepositionAnchor(cell.col, cell.row)) {
                drawPath(diamond, color = Color(0x8033C6FF))
                drawPath(diamond, color = Color(0xFF1E88E5), style = Stroke(width = 2.5f * scale))
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
                OccupantType.CARROT -> {
                    drawCarrotTile(cx, cy, w, h, cell.growthPhase, cell.growthProgress, cell.id)
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

        // Central 2x2 farm building. Position computed the same way the old
        // inline code did, centered on the 2x2 footprint. The building itself
        // is a real parsed/flat-shaded 3D barn mesh (precomputed once in
        // BarnMesh, not per-frame): each triangle's normalized (tile-relative)
        // offsets are scaled by tileW (uniformly for both axes — see BarnMesh
        // doc for why) and the current scale, then filled in the mesh's
        // precomputed back-to-front order. Wrapped in a lambda (rather than
        // drawn unconditionally here) so it can be interleaved with the cow
        // below in true depth order — see the painter's-algorithm dispatch
        // after both lambdas.
        val drawBarn: () -> Unit = {
            val centerCol = buildingAnchorCol + 0.5f
            val centerRow = buildingAnchorRow + 0.5f
            val bBaseCx = originOffset.x + GridMath.isoX(centerCol, centerRow, tileW)
            val bBaseCy = originOffset.y + GridMath.isoY(centerCol, centerRow, tileH)
            val bx = (bBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val by = (bBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y

            // Soft contact shadow, ground-plane-flat (an iso-proportioned ellipse, not
            // a screen-space circle) under the barn's floor anchor. This is most of
            // what was making the barn read as "a photo pasted on the game": a flat-
            // shaded mesh with a hard silhouette and no contact with the ground below
            // it looks like a cutout sticker. A soft dark-to-transparent radial falloff
            // anchors it to the dirt instead of floating over it.
            val shadowRx = tileW * scale * 1.05f
            val shadowRy = tileH * scale * 0.85f
            drawOval(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color(0x66231A0F),
                        0.7f to Color(0x33231A0F),
                        1f to Color(0x00231A0F)
                    ),
                    center = Offset(bx, by),
                    radius = shadowRx
                ),
                topLeft = Offset(bx - shadowRx, by - shadowRy),
                size = Size(shadowRx * 2f, shadowRy * 2f)
            )

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
        // Position is driven by the free-floating cowCol/cowRow coroutine above,
        // which already keeps it clear of the barn's footprint (see the
        // rectangle-avoidance path planning there) — this lambda only handles
        // the remaining "which one paints on top" question for near-barn tiles.
        val drawCow: () -> Unit = {
            val cowBaseCx = originOffset.x + GridMath.isoX(cowCol, cowRow, tileW)
            val cowBaseCy = originOffset.y + GridMath.isoY(cowCol, cowRow, tileH)
            val ccx = (cowBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val ccy = (cowBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            val walkPhase = cowAnimMs / 140f
            val stride = kotlin.math.sin(walkPhase)
            val bob = kotlin.math.abs(stride) * 3f
            // The cow silhouette in drawWanderingCow spans ~77 units wide at its
            // 46px reference tile (its horns/tail overshoot the body's core box),
            // so dividing by 46 (1 tile-width = 1 unit) rendered it ~1.7x a cell.
            // Dividing by 77 makes it match one cell; doubling that (154) makes
            // the cow half a cell wide.
            val cowUnitScale = (tileW * scale) / 154f
            drawWanderingCow(
                cx = ccx,
                cy = ccy - 18f * cowUnitScale,
                unitScale = cowUnitScale,
                facingRight = cowFacingRight,
                stride = stride,
                bob = bob
            )
        }

        // True painter's-algorithm ordering between the two solid objects: the
        // barn's iso-depth is its footprint center (buildingAnchorCol/Row + 1,
        // matching the +0.5/+0.5 centerCol/centerRow above), the cow's is its
        // own col+row. Whichever has the smaller col+row is further from the
        // camera in this 2:1 iso projection and must paint first, so the
        // nearer object's sprite correctly covers it on any tile where their
        // screen-space silhouettes still touch (e.g. right at the barn's
        // walls), instead of the cow always winning regardless of position.
        val barnDepth = buildingAnchorCol + buildingAnchorRow + 1
        val cowDepth = cowCol + cowRow
        if (cowCol >= 0f && cowDepth < barnDepth) {
            drawCow()
            drawBarn()
        } else {
            drawBarn()
            if (cowCol >= 0f) drawCow()
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
                    // Anchoring at the border cell's own center (col, row) split
                    // each fence module across the cell/outside boundary. Shift
                    // the anchor half a cell outward along whichever axis is
                    // pinned at an extreme, so the fence module sits fully
                    // outside the playable ring instead of straddling it. Corner
                    // cells are pinned on both axes and get both shifts, landing
                    // the post at the outer corner point.
                    val fenceCol = col + when (col) {
                        0 -> -0.5f
                        gridConfig.cols - 1 -> 0.5f
                        else -> 0f
                    }
                    val fenceRow = row + when (row) {
                        0 -> -0.5f
                        gridConfig.rows - 1 -> 0.5f
                        else -> 0f
                    }
                    val fBaseCx = originOffset.x + GridMath.isoX(fenceCol, fenceRow, tileW)
                    val fBaseCy = originOffset.y + GridMath.isoY(fenceCol, fenceRow, tileH)
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

/**
 * Renders a carrot cell using the same full-tile procedural approach as
 * [drawWheatTile] — soil furrows, seed dots, scattered strokes filling the
 * whole diamond footprint — but with curved, feathery leaf fronds instead of
 * straight wheat blades, and orange root accents woven through every phase
 * (not just Mature): a couple of the Seed phase's seed dots are warm orange
 * instead of plain soil brown, Sprout/Plant show small orange root-tips
 * breaking the surface, and Mature has denser shoulders each with a short
 * tapering root-tip stroke. Ported 1:1 from design option A ("wheat-style
 * continuation") in docs/previews/carrot-preview.html, including its
 * "more orange" revision. Deterministic per-[seed] like [drawWheatTile], via
 * a distinct multiplier (`* 37 + ... * 11`) on the shared [scatterPointsInDiamond]
 * seed so the two crops' scatter layouts never accidentally coincide.
 */
private fun DrawScope.drawCarrotTile(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    phase: GrowthPhase,
    progress: Float,
    seed: Int
) {
    val scaleFactor = (w / 56f).coerceAtLeast(0.05f)
    val furrowDir = run {
        val len = hypot(w, h)
        if (len == 0f) Offset(1f, 0f) else Offset(w / len, h / len)
    }
    val soilDark = Color(0xFF6D4C41)
    val seedFleckColors = listOf(
        Color(0xFF7A4A1E), Color(0xFF7A4A1E), Color(0xFF7A4A1E),
        Color(0xFFD9812F), Color(0xFFD9812F)
    )

    fun drawFurrows(count: Int, alpha: Float, minLocalY: Float) {
        val pts = scatterPointsInDiamond(seed * 37 + phase.ordinal * 11 + 101, count, w, h, margin = 0.75f)
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
        val pts = scatterPointsInDiamond(seed * 37 + phase.ordinal * 11 + 202, count, w, h, margin = 0.7f)
        val rnd = Random(seed * 37 + phase.ordinal * 11 + 203)
        for (p in pts) {
            drawCircle(
                color = seedFleckColors[rnd.nextInt(seedFleckColors.size)],
                radius = 1.4f * scaleFactor,
                center = Offset(cx + p.x, cy + p.y)
            )
        }
    }

    fun drawLeafFronds(count: Int, seedOffset: Int, heightFactor: Float, colors: List<Color>) {
        val pts = scatterPointsInDiamond(seed * 37 + phase.ordinal * 11 + seedOffset, count, w, h, margin = 0.82f)
        val rnd = Random(seed * 37 + phase.ordinal * 11 + seedOffset + 1)
        for (p in pts) {
            val base = Offset(cx + p.x, cy + p.y)
            val frondH = min(w, h) * (0.22f + rnd.nextFloat() * 0.14f) * heightFactor
            val tilt = (rnd.nextFloat() - 0.5f) * frondH * 0.6f
            val color = colors[rnd.nextInt(colors.size)]
            val stroke = Stroke(width = 1.8f * scaleFactor, cap = StrokeCap.Round)
            // Two thin curved fronds instead of one straight blade — reads as
            // a ferny carrot top rather than a wheat blade.
            val frondA = Path().apply {
                moveTo(base.x, base.y)
                quadraticTo(base.x + tilt * 0.5f, base.y - frondH * 0.6f, base.x + tilt, base.y - frondH)
            }
            drawPath(frondA, color = color, style = stroke)
            val frondB = Path().apply {
                moveTo(base.x, base.y)
                quadraticTo(base.x - tilt * 0.3f, base.y - frondH * 0.5f, base.x - tilt * 0.6f, base.y - frondH * 0.85f)
            }
            drawPath(frondB, color = color, style = stroke)
        }
    }

    fun drawRootTips(count: Int, seedOffset: Int, radiusFactor: Float) {
        // Small glimpses of orange root breaking the soil under the leaf
        // scatter — carries the crop's orange identity through Sprout/Plant
        // instead of only appearing once Mature.
        val pts = scatterPointsInDiamond(seed * 37 + phase.ordinal * 11 + seedOffset, count, w, h, margin = 0.6f)
        for (p in pts) {
            val r = min(w, h) * radiusFactor
            drawOval(
                color = Color(0xFFEE8B33),
                topLeft = Offset(cx + p.x - r, cy + p.y - r * 0.6f),
                size = Size(r * 2f, r * 1.2f)
            )
        }
    }

    fun drawShoulders(count: Int) {
        val pts = scatterPointsInDiamond(seed * 37 + phase.ordinal * 11 + 707, count, w, h, margin = 0.55f)
        val rnd = Random(seed * 37 + phase.ordinal * 11 + 708)
        for (p in pts) {
            val base = Offset(cx + p.x, cy + p.y)
            val rw = min(w, h) * (0.09f + rnd.nextFloat() * 0.03f)
            drawOval(
                color = Color(0xFFE8792A),
                topLeft = Offset(base.x - rw, base.y - rw * 0.6f),
                size = Size(rw * 2f, rw * 1.2f)
            )
            drawOval(
                color = Color(0x26000000),
                topLeft = Offset(base.x - rw, base.y - rw * 0.6f),
                size = Size(rw * 2f, rw * 1.2f),
                style = Stroke(width = 1f)
            )
            // A short tapering root-tip stroke below the shoulder, so it reads
            // as a carrot breaking the surface rather than a flat orange dot.
            drawLine(
                color = Color(0xFFD46A22),
                start = Offset(base.x, base.y + rw * 0.3f),
                end = Offset(base.x + (rnd.nextFloat() - 0.5f) * rw, base.y + rw * 1.6f),
                strokeWidth = rw * 0.5f,
                cap = StrokeCap.Round
            )
        }
    }

    when (phase) {
        GrowthPhase.SEED -> {
            drawFurrows(count = 6, alpha = 0.85f, minLocalY = -h)
            drawSeedDots(count = 5)
        }
        GrowthPhase.SPROUT -> {
            drawFurrows(count = 3, alpha = 0.55f, minLocalY = h * 0.05f)
            val heightFactor = 0.55f + 0.25f * progress
            drawLeafFronds(
                count = 9,
                seedOffset = 303,
                heightFactor = heightFactor,
                colors = listOf(Color(0xFF66BB6A), Color(0xFF43A047), Color(0xFF2E7D32))
            )
            drawRootTips(count = 2, seedOffset = 909, radiusFactor = 0.045f)
        }
        GrowthPhase.PLANT -> {
            drawFurrows(count = 2, alpha = 0.3f, minLocalY = h * 0.3f)
            val heightFactor = 0.8f + 0.25f * progress
            drawLeafFronds(
                count = 13,
                seedOffset = 404,
                heightFactor = heightFactor,
                colors = listOf(Color(0xFF43A047), Color(0xFF2E7D32), Color(0xFF1B5E20))
            )
            drawRootTips(count = 4, seedOffset = 910, radiusFactor = 0.06f)
        }
        GrowthPhase.MATURE -> {
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
            drawLeafFronds(
                count = 13,
                seedOffset = 505,
                heightFactor = 1f,
                colors = listOf(Color(0xFF2E7D32), Color(0xFF1B5E20), Color(0xFF43A047))
            )
            // Full carrot shoulders + root-tip strokes — the tile should read
            // unmistakably orange/carrot at Mature, not just green.
            drawShoulders(count = 8)
        }
        GrowthPhase.NONE -> {
            // Carrot cells should never be NONE, but keep the branch for
            // exhaustiveness — nothing to draw.
        }
    }
}
