package com.farmmathbuilder.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale as drawScale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.data.entity.DecorationEntity
import com.farmmathbuilder.app.domain.AnimalGrowthStage
import com.farmmathbuilder.app.domain.AnimalUiModel
import com.farmmathbuilder.app.domain.BarnMesh
import com.farmmathbuilder.app.domain.DecorationSide
import com.farmmathbuilder.app.domain.DecorationType
import com.farmmathbuilder.app.domain.GridConfig
import com.farmmathbuilder.app.domain.GridMath
import com.farmmathbuilder.app.domain.GrowthPhase
import com.farmmathbuilder.app.domain.OccupantType
import com.farmmathbuilder.app.domain.isCrop
import com.farmmathbuilder.app.domain.PathType
import com.farmmathbuilder.app.domain.UiCell
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
    cows: List<AnimalUiModel> = emptyList(),
    onCowTapped: (animalId: Int) -> Unit = {},
    decorations: List<DecorationEntity> = emptyList(),
    placingDecorationType: DecorationType? = null,
    onDecorationPlacementTarget: (side: DecorationSide, alongFraction: Float) -> Unit = { _, _ -> }
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val tileWidthDp = 56f
    val tileHeightDp = 30f

    val barnTriangles = remember { BarnMesh.load() }

    // One independent wandering state per owned cow (see CowWanderState/
    // rememberCowWanderState below) — each cow gets its own free-floating
    // col/row position animated by its own coroutine, keyed by animal id so
    // buying/breeding a new cow starts a fresh wander loop without disturbing
    // any existing cow's position.
    val cowRenders: List<Pair<AnimalUiModel, CowWanderState>> = cows.map { cow ->
        key(cow.id) { cow to rememberCowWanderState(cow.id, gridConfig) }
    }

    // Free-running clock for the river's water animation (highlight glints,
    // sparkles, drifting spring mist) — independent of the cow's own clock
    // since it must keep animating even while she's paused/grazing.
    var riverAnimMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            riverAnimMs = withFrameMillis { it }.toFloat()
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
            .pointerInput(cells, gridConfig, cowRenders, placingDecorationType) {
                detectTapGestures { tapOffset ->
                    val canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val contentPoint = (tapOffset - canvasCenter - pan) / scale + canvasCenter
                    val tileW = tileWidthDp.dp.toPx()
                    val tileH = tileHeightDp.dp.toPx()
                    val gridOriginOffset = gridOrigin(canvasCenter, tileW, tileH, gridConfig.cols, gridConfig.rows)

                    // Decoration placement mode takes over the whole canvas: invert
                    // the isometric projection to recover a floating (col, row) for
                    // the tap (valid anywhere, including outside the grid array,
                    // unlike the cell-by-cell hit test below), then resolve it to a
                    // border side + fraction-along-that-side. An in-bounds tap (not
                    // outside the fence) is silently ignored — placement mode stays
                    // active so the player can just tap again.
                    if (placingDecorationType != null) {
                        val dx = contentPoint.x - gridOriginOffset.x
                        val dy = contentPoint.y - gridOriginOffset.y
                        val tapCol = dx / tileW + dy / tileH
                        val tapRow = dy / tileH - dx / tileW
                        resolveDecorationTarget(tapCol, tapRow, gridConfig.cols, gridConfig.rows)?.let { (side, fraction) ->
                            onDecorationPlacementTarget(side, fraction)
                        }
                        return@detectTapGestures
                    }

                    // Each cow is only tappable while hungry (otherwise purely
                    // decorative) — checked first so a tap "on her" never also
                    // falls through to the cell beneath her feet. Hit target is a
                    // generous circle around her visual center (shifted up from
                    // her feet, matching the -18*unitScale draw offset below),
                    // not her exact silhouette. Later cows in the list win ties
                    // (matches draw order, back-to-front isn't relevant here
                    // since hit circles rarely overlap).
                    val tappedCow = cowRenders.lastOrNull { (cow, state) ->
                        cow.isHungry && state.col >= 0f && run {
                            val cowBaseCx = gridOriginOffset.x + GridMath.isoX(state.col, state.row, tileW)
                            val cowBaseCy = gridOriginOffset.y + GridMath.isoY(state.col, state.row, tileH)
                            val dx = contentPoint.x - cowBaseCx
                            val dy = contentPoint.y - (cowBaseCy - tileH * 0.3f)
                            val hitRadius = tileW * 0.55f
                            dx * dx + dy * dy <= hitRadius * hitRadius
                        }
                    }

                    if (tappedCow != null) {
                        onCowTapped(tappedCow.first.id)
                        return@detectTapGestures
                    }

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
                    hitCell?.let { onCellTapped(it.id) }
                }
            }
    ) {
        val canvasCenter = Offset(size.width / 2f, size.height / 2f)
        val tileW = tileWidthDp.dp.toPx()
        val tileH = tileHeightDp.dp.toPx()
        val originOffset = gridOrigin(canvasCenter, tileW, tileH, gridConfig.cols, gridConfig.rows)

        // Continuous ground plane covering the entire visible canvas (unaffected by
        // scale/pan — drawn once in raw canvas coordinates), so panning/zooming never
        // reveals empty space around the grid: the barn and every crop always sit on
        // green ground. The fence (drawn last, below) is now the only visual cue for
        // where the buildable/playable area actually ends.
        drawRect(color = Color(0xFF9CCC65), size = size)

        // Player-placed map decorations (founder request 2026-08-18: "accidentes
        // geográficos" shop — RIVER and CAVE today, docs/previews/river-woods-preview.html
        // and docs/previews/cave-bear-preview.html). Each one's position is stored
        // relative (border side + fraction along it, see DecorationEntity's doc)
        // and re-projected onto gridConfig.cols/rows every frame, exactly like the
        // fence posts — so it always sits outside the buildable ring and stays in
        // the same relative spot as the map expands, never overlapping a playable
        // cell. Drawn before every gameplay element (cells, barn, cow, fence, all
        // below) so decorations can never render on top of the fence (founder
        // request 2026-08-18: "las vallas siempre han de quedar encima de los
        // accidentes geográficos") — this loop always runs first, unconditionally,
        // not through the depth-sorted painter's-algorithm list those use, so
        // there's no depth value to get wrong here the way the barn's briefly did.
        for (decoration in decorations) {
            when (decoration.type) {
                DecorationType.RIVER -> drawRiverAndWoodsBackdrop(
                    originOffset = originOffset,
                    canvasCenter = canvasCenter,
                    scale = scale,
                    pan = pan,
                    tileW = tileW,
                    tileH = tileH,
                    gridConfig = gridConfig,
                    side = decoration.side,
                    alongFraction = decoration.alongFraction,
                    animMs = riverAnimMs
                )
                DecorationType.CAVE -> drawCaveAndBear(
                    originOffset = originOffset,
                    canvasCenter = canvasCenter,
                    scale = scale,
                    pan = pan,
                    tileW = tileW,
                    tileH = tileH,
                    gridConfig = gridConfig,
                    side = decoration.side,
                    alongFraction = decoration.alongFraction,
                    decorationId = decoration.id
                )
            }
        }

        for (cell in cells.sortedBy { it.col + it.row }) {
            val baseCx = originOffset.x + GridMath.isoX(cell.col, cell.row, tileW)
            val baseCy = originOffset.y + GridMath.isoY(cell.col, cell.row, tileH)
            val cx = (baseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val cy = (baseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            val w = tileW * scale
            val h = tileH * scale

            val fillColor = when {
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

            when (cell.occupantType) {
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
                    if (cell.isBuildable) {
                        textPaint.textSize = 14f * scale
                        textPaint.color = android.graphics.Color.argb(90, 0, 0, 0)
                        drawContext.canvas.nativeCanvas.drawText("+", cx, cy + 5f * scale, textPaint)
                        textPaint.color = android.graphics.Color.BLACK
                    }
                }
            }
        }

        // Farm Building: lives permanently outside the fenced play area, touching
        // the fence along its bottom edge (founder request 2026-08-18: "fuera de
        // las vallas, pero pegadas a ellas" — frees up the 2x2 of grid cells it
        // used to occupy inside the fence, and removes "move barn" since it's no
        // longer player-placeable). Position is derived purely from gridConfig
        // every frame — same live-recompute pattern as the river/woods backdrop
        // and the fence itself — so it stays centered and attached to the fence
        // through every map expansion with no persisted anchor to migrate. Still
        // the "Grand Timber Frame Barn" — a procedurally-built, flat-shaded 3D
        // mesh (precomputed once in BarnMesh, not per-frame, per founder
        // request to keep that model): each triangle's normalized (tile-
        // relative) offsets are scaled by tileW
        // (uniformly for both axes — see BarnMesh doc for why) and the current
        // scale, then filled in the mesh's precomputed back-to-front order.
        val barnCol = gridConfig.cols / 2f - 0.5f
        val barnRow = gridConfig.rows - 0.5f + 1.2f
        val drawBarn: () -> Unit = {
            val bBaseCx = originOffset.x + GridMath.isoX(barnCol, barnRow, tileW)
            val bBaseCy = originOffset.y + GridMath.isoY(barnCol, barnRow, tileH)
            val bx = (bBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val by = (bBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y

            // Soft contact shadow, ground-plane-flat (an iso-proportioned ellipse, not
            // a screen-space circle) under the barn's floor anchor — grounds the flat-
            // shaded mesh instead of it reading as a cutout sticker floating over the
            // ground.
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

        // Decorative wandering cows: not part of `cells`, and only ever hit-tested
        // by the tap handler above while a given cow is hungry — otherwise purely
        // cosmetic. Each cow's position is driven by its own free-floating
        // CowWanderState coroutine (rememberCowWanderState above) — this builds
        // one draw lambda per cow, handling a smaller scale for calves and the
        // floating hunger icon when needed.
        fun drawCowAt(cow: AnimalUiModel, state: CowWanderState): () -> Unit = {
            val cowBaseCx = originOffset.x + GridMath.isoX(state.col, state.row, tileW)
            val cowBaseCy = originOffset.y + GridMath.isoY(state.col, state.row, tileH)
            val ccx = (cowBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x
            val ccy = (cowBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            val walkPhase = state.animMs / 140f
            val stride = kotlin.math.sin(walkPhase)
            val bob = kotlin.math.abs(stride) * 3f
            // The cow silhouette in drawWanderingCow spans ~77 units wide at its
            // 46px reference tile (its horns/tail overshoot the body's core box),
            // so dividing by 46 (1 tile-width = 1 unit) rendered it ~1.7x a cell.
            // Dividing by 77 makes it match one cell; doubling that (154) makes
            // the cow half a cell wide. A calf (no separate art yet — see
            // founder plan) reuses the same silhouette at a smaller scale, the
            // same "growth phase via scale" shortcut the river/woods preview's
            // trees use for now.
            val calfFactor = if (cow.stage == AnimalGrowthStage.CALF) 0.62f else 1f
            val cowUnitScale = (tileW * scale) / 154f * calfFactor
            val cowAnchorCx = ccx
            val cowAnchorCy = ccy - 18f * cowUnitScale
            drawWanderingCow(
                cx = cowAnchorCx,
                cy = cowAnchorCy,
                unitScale = cowUnitScale,
                facingRight = state.facingRight,
                stride = stride,
                bob = bob
            )
            if (cow.isHungry) {
                drawCowHungerIcon(
                    cx = cowAnchorCx,
                    cy = cowAnchorCy,
                    unitScale = cowUnitScale,
                    facingRight = state.facingRight,
                    animMs = state.animMs
                )
            }
        }

        // True painter's-algorithm ordering across *every* depth-sorted object in
        // the scene — barn, every cow, and each individual fence segment —
        // collected into one (depth, drawAction) list and painted back-to-front.
        // Depth convention matches GridMath.isoY: larger col+row is further
        // down-screen, i.e. nearer the camera, i.e. painted later.
        //
        // The barn's own depth is deliberately NOT its actual barnCol+barnRow
        // position any more: now that it lives permanently outside/south of the
        // fence (see drawBarn's doc), it should always paint in front of the
        // *entire* fence ring, corners included — but a corner post's depth is
        // inflated by being offset on both axes at once (col+0.5 AND row+0.5),
        // so it can exceed barnCol+barnRow even though the corner isn't actually
        // any nearer the camera than the rest of the south edge (founder
        // screenshot 2026-08-18: a corner post slicing across the barn's roof).
        // cols+rows is always >= every fenceDepth() value below (whose max is
        // exactly cols+rows-1, at the bottom-right corner), so this guarantees
        // the barn wins against every fence segment unconditionally.
        val depthDrawables = mutableListOf<Pair<Float, () -> Unit>>()
        depthDrawables.add((gridConfig.cols + gridConfig.rows).toFloat() to drawBarn)
        for ((cow, state) in cowRenders) {
            if (state.col >= 0f) depthDrawables.add((state.col + state.row) to drawCowAt(cow, state))
        }

        // Decorative fence lining the buildable-area boundary: flat 2D posts +
        // a continuous rail, replacing the earlier fence.fbx-based 3D mesh
        // (design option A from docs/previews/fence-corner-preview.html,
        // founder-approved 2026-08-14). The 3D mesh's per-cell straight-run
        // modules could never self-miter at the 4 corners (each was built to
        // continue straight, not turn 90°, so two rotated copies just sat near
        // each other without joining — founder screenshots). A flat post/rail
        // grammar (same "programmatic 2D" family as drawWheatTile/
        // drawCarrotTile) sidesteps that entirely: corners are just a slightly
        // bigger post, and the rail is one continuous line through every
        // border cell's own anchor point, so there is no separate rotated
        // piece that could fail to connect.
        //
        // One post per cell on the grid's own outermost ring (col/row == 0 or
        // cols-1/rows-1), i.e. the true edge of the cols x rows array.
        // GridMath.isBuildable excludes this same border ring, so the fence
        // always marks the true limit of where the player can act. Recomputed
        // live from gridConfig so it follows map expansion automatically (no
        // persistence needed).
        //
        // Perimeter walk (top L->R, right T->B, bottom R->L, left B->T) visits
        // every border cell exactly once in boundary order, so consecutive
        // entries are always adjacent posts — required for the rail segments
        // (each post draws the segment leading to the *next* post) to form one
        // unbroken loop instead of a scatter of disconnected pieces.
        val perimeterCells = mutableListOf<Pair<Int, Int>>()
        for (col in 0 until gridConfig.cols) perimeterCells.add(col to 0)
        for (row in 1 until gridConfig.rows) perimeterCells.add((gridConfig.cols - 1) to row)
        for (col in gridConfig.cols - 2 downTo 0) perimeterCells.add(col to (gridConfig.rows - 1))
        for (row in gridConfig.rows - 2 downTo 1) perimeterCells.add(0 to row)

        // Anchoring at the border cell's own center (col, row) would split each
        // post across the cell/outside boundary — shift half a cell outward
        // along whichever axis is pinned at an extreme, so the fence sits fully
        // outside the playable ring instead of straddling it. Corner cells are
        // pinned on both axes and get both shifts, landing the post at the
        // outer corner point.
        fun fenceDepth(col: Int, row: Int): Float {
            val fenceCol = col + when (col) { 0 -> -0.5f; gridConfig.cols - 1 -> 0.5f; else -> 0f }
            val fenceRow = row + when (row) { 0 -> -0.5f; gridConfig.rows - 1 -> 0.5f; else -> 0f }
            return fenceCol + fenceRow
        }
        fun fencePoint(col: Int, row: Int): Offset {
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
            return Offset(
                (fBaseCx - canvasCenter.x) * scale + canvasCenter.x + pan.x,
                (fBaseCy - canvasCenter.y) * scale + canvasCenter.y + pan.y
            )
        }

        val fenceUnitScale = (tileW * scale) / 112f
        for (i in perimeterCells.indices) {
            val (col, row) = perimeterCells[i]
            val isCorner = (col == 0 || col == gridConfig.cols - 1) && (row == 0 || row == gridConfig.rows - 1)
            val point = fencePoint(col, row)
            val (nextCol, nextRow) = perimeterCells[(i + 1) % perimeterCells.size]
            val nextPoint = fencePoint(nextCol, nextRow)
            depthDrawables.add(
                fenceDepth(col, row) to {
                    drawFenceRailSegment(point, nextPoint, fenceUnitScale)
                    drawFencePost(point, fenceUnitScale, isCorner)
                }
            )
        }

        for ((_, draw) in depthDrawables.sortedBy { it.first }) draw()
    }
}

/** One cow's free-floating wander position/animation, mutated frame-by-frame by
 * its own coroutine (see [rememberCowWanderState]) and read every draw frame by
 * [FarmGridCanvas]'s Canvas — plain compose-state-backed properties (not a data
 * class) so mutating one field doesn't require rebuilding the whole object.
 * -1f col/row is the "uninitialized" sentinel (valid col/row are always >= 0). */
private class CowWanderState {
    var col by mutableFloatStateOf(-1f)
    var row by mutableFloatStateOf(-1f)
    var facingRight by mutableStateOf(true)
    var animMs by mutableFloatStateOf(0f)
}

/**
 * Runs one cow's independent wander loop and returns its live [CowWanderState]
 * for the Canvas to read. Keyed on [cowId] so buying/breeding a new cow starts
 * its own fresh loop without disturbing any other cow, and on [gridConfig] so
 * every cow's loop restarts (and shifts its position, see below) when the map
 * expands — same behavior the single-cow version of this code had. No obstacle
 * to route around any more: the Farm Building now lives entirely outside the
 * fenced play area (see the drawBarn draw-lambda above), so a straight-line
 * walk between any two buildable cells never needs detouring.
 */
@Composable
private fun rememberCowWanderState(cowId: Int, gridConfig: GridConfig): CowWanderState {
    val state = remember(cowId) { CowWanderState() }
    var prevGridConfig by remember(cowId) { mutableStateOf<GridConfig?>(null) }

    LaunchedEffect(cowId, gridConfig) {
        // expandGrid() shifts every existing cell by +1 col/+1 row to keep the
        // fence centered (see FarmRepository.expandGrid) — shift this cow's
        // free-floating position the same way so it doesn't visually teleport
        // relative to the fence when the map grows.
        prevGridConfig?.let { prev ->
            if (gridConfig.cols != prev.cols) {
                val shift = (gridConfig.cols - prev.cols) / 2f
                state.col += shift
                state.row += shift
            }
        }
        prevGridConfig = gridConfig

        fun randomWanderTarget(): Offset {
            while (true) {
                val col = Random.nextInt(gridConfig.cols)
                val row = Random.nextInt(gridConfig.rows)
                if (GridMath.isBuildable(col, row, gridConfig.cols, gridConfig.rows)) {
                    return Offset(col.toFloat(), row.toFloat())
                }
            }
        }

        if (state.col < 0f) {
            val start = randomWanderTarget()
            state.col = start.x
            state.row = start.y
        }

        while (true) {
            // "Graze" pause between walks, ticking the anim clock so the tail/legs
            // keep a subtle idle sway instead of freezing.
            var frameMs = withFrameMillis { it }
            val pauseUntilMs = frameMs + Random.nextInt(800, 2400)
            while (frameMs < pauseUntilMs) {
                state.animMs = frameMs.toFloat()
                frameMs = withFrameMillis { it }
            }

            val target = randomWanderTarget()
            val tilesPerMs = 0.5f / 1000f

            val startCol = state.col
            val startRow = state.row
            val dCol = target.x - startCol
            val dRow = target.y - startRow
            val dist = hypot(dCol, dRow)
            if (dist < 0.05f) continue
            state.facingRight = dCol >= 0f

            val durationMs = dist / tilesPerMs
            val moveStartMs = frameMs
            while (true) {
                frameMs = withFrameMillis { it }
                state.animMs = frameMs.toFloat()
                val t = ((frameMs - moveStartMs) / durationMs).coerceIn(0f, 1f)
                state.col = startCol + dCol * t
                state.row = startRow + dRow * t
                if (t >= 1f) break
            }
        }
    }
    return state
}

/**
 * The "chibi sticker" cow design (option B of docs/previews/cow-preview.html),
 * ported 1:1 from that preview's `drawCowB` canvas routine: rounded 2D shapes
 * (no mesh/3D), all offsets tuned against that preview's 46px reference tile
 * width, so [unitScale] should be `(tileWidthPx * zoomScale) / 46f` — the same
 * "1 tile-width = 1 unit" convention BarnMesh triangles/the fence/the river use. [cx]/[cy]
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

/**
 * Floating hunger indicator drawn just above the cow's head (see CowHunger):
 * a small cream badge, same diameter as the head ellipse in [drawWanderingCow]
 * (`u(30f)` tall, so radius `u(15f)`) — deliberately sized to match her body
 * rather than an oversized attention-grabbing icon, with a gentle vertical
 * bob independent of her walk cycle. [cx]/[cy] must be the same
 * shoulder/back anchor point passed to [drawWanderingCow] for this frame, and
 * [facingRight] must match too, since the head (and therefore the icon) sits
 * on the mirrored side when she's facing left.
 */
private fun DrawScope.drawCowHungerIcon(
    cx: Float,
    cy: Float,
    unitScale: Float,
    facingRight: Boolean,
    animMs: Float
) {
    fun u(v: Float) = v * unitScale

    // Head center/radius mirror drawWanderingCow's own head placement
    // (headCenter = anchor + Offset(u(24f), u(-8f)), size 34x30) so the badge
    // sits directly above it regardless of facing direction.
    val headOffsetX = if (facingRight) u(24f) else -u(24f)
    val headCenterY = cy + u(-8f)
    val headRadiusY = u(15f)
    val iconRadius = u(15f)
    val bounce = kotlin.math.sin(animMs / 260f) * u(2f)
    val iconCx = cx + headOffsetX
    val iconCy = headCenterY - headRadiusY - iconRadius - u(4f) + bounce

    val ink = Color(0xFF3A332B)
    val badgeCream = Color(0xFFFFFAF0)
    val wheatGold = Color(0xFFFFC107)

    drawCircle(color = badgeCream, radius = iconRadius, center = Offset(iconCx, iconCy))
    drawCircle(color = ink, radius = iconRadius, center = Offset(iconCx, iconCy), style = Stroke(width = u(1.6f)))

    // A tiny wheat-sheaf fan reads as "food" at a glance without needing an
    // emoji font/image asset, consistent with every other Canvas-drawn shape
    // in this file.
    val baseY = iconCy + iconRadius * 0.35f
    for (angleDeg in floatArrayOf(-24f, 0f, 24f)) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val len = iconRadius * 0.85f
        val dx = (kotlin.math.sin(rad) * len).toFloat()
        val dy = (-kotlin.math.cos(rad) * len).toFloat()
        drawLine(
            color = wheatGold,
            start = Offset(iconCx, baseY),
            end = Offset(iconCx + dx, baseY + dy),
            strokeWidth = u(2f),
            cap = StrokeCap.Round
        )
    }
}

/**
 * Boundary fence post — design option A from docs/previews/fence-corner-preview.html
 * (founder-approved 2026-08-14, replacing the earlier fence.fbx 3D mesh, which
 * could never self-miter at corners since its module was a straight run built
 * to continue straight, not turn 90°). A simple picket silhouette (body +
 * pointed spike top); [isCorner] posts are drawn larger, matching the
 * preview's "one dedicated corner post" idea — a shared piece both boundary
 * runs visually terminate into, rather than two independently-rotated pieces
 * that may not meet. [anchor] is the post's ground-level base point (same
 * "diamond center" convention every other tile element uses).
 */
private fun DrawScope.drawFencePost(anchor: Offset, unitScale: Float, isCorner: Boolean) {
    fun u(v: Float) = v * unitScale
    val h = if (isCorner) 32f else 26f
    val w = if (isCorner) 10f else 7f
    val bodyColor = Color(0xFF6D5638)
    val spikeColor = Color(0xFF5A4529)

    val bodyPath = Path().apply {
        moveTo(anchor.x - u(w / 2f), anchor.y)
        lineTo(anchor.x + u(w / 2f), anchor.y)
        lineTo(anchor.x + u(w / 2f), anchor.y - u(h))
        lineTo(anchor.x - u(w / 2f), anchor.y - u(h))
        close()
    }
    drawPath(bodyPath, color = bodyColor)

    val spikePath = Path().apply {
        moveTo(anchor.x - u(w / 2f), anchor.y - u(h))
        lineTo(anchor.x, anchor.y - u(h) - u(w * 0.6f))
        lineTo(anchor.x + u(w / 2f), anchor.y - u(h))
        close()
    }
    drawPath(spikePath, color = spikeColor)
}

/** Rail segment connecting one boundary post to the next — drawing one per
 * post (leading to its successor in perimeter-walk order, see the fence loop
 * in the main draw scope) chains into a single unbroken loop around the whole
 * boundary, corners included, since consecutive segments always share an
 * endpoint. Sits partway up the post height, not at its base, so it reads as
 * a horizontal rail rather than a line drawn in the dirt. */
private fun DrawScope.drawFenceRailSegment(from: Offset, to: Offset, unitScale: Float) {
    fun u(v: Float) = v * unitScale
    drawLine(
        color = Color(0xFF8A6A42),
        start = Offset(from.x, from.y - u(12f)),
        end = Offset(to.x, to.y - u(12f)),
        strokeWidth = u(5f),
        cap = StrokeCap.Round
    )
}

/**
 * River + woods backdrop (founder-approved design, docs/previews/river-woods-preview.html):
 * a rock spring feeds a sinuous, reed-banked river that curves down to a
 * corner and widens into a small lily-pad pool tucked under the first tree of
 * a woods cluster. Every position is derived from [gridConfig] (via
 * [riverPathGrid], in col/row grid units, not pixels) and re-projected through
 * the same origin/scale/pan transform as every other element in this file, so
 * the whole backdrop automatically slides outward and stays outside the
 * boundary fence as the map expands — no separate "reposition on expand"
 * bookkeeping needed, mirroring how the fence and the wandering cow already
 * track [gridConfig]. [side]/[alongFraction] place it on any of the 4 border
 * edges (founder request 2026-08-18: player-placeable map decorations, see
 * DecorationEntity) — originally this was hardcoded to the top edge at a fixed
 * 0.38 fraction; [riverPathGrid] now generalizes that same shape to any edge.
 */
private fun DrawScope.drawRiverAndWoodsBackdrop(
    originOffset: Offset,
    canvasCenter: Offset,
    scale: Float,
    pan: Offset,
    tileW: Float,
    tileH: Float,
    gridConfig: GridConfig,
    side: DecorationSide,
    alongFraction: Float,
    animMs: Float
) {
    fun toScreen(col: Float, row: Float): Offset {
        val bx = originOffset.x + GridMath.isoX(col, row, tileW)
        val by = originOffset.y + GridMath.isoY(col, row, tileH)
        return Offset(
            (bx - canvasCenter.x) * scale + canvasCenter.x + pan.x,
            (by - canvasCenter.y) * scale + canvasCenter.y + pan.y
        )
    }

    val pts = riverPathGrid(gridConfig.cols, gridConfig.rows, side, alongFraction).map { toScreen(it.x, it.y) }
    // Preview art (docs/previews/river-woods-preview.html) was tuned against a
    // 112px reference tile width — same "1 tile-width = 1 unit" convention the
    // fence (fenceUnitScale) and cow (cowUnitScale) already use.
    val unitScale = (tileW * scale) / 112f

    drawRiverSpring(pts.first(), unitScale, animMs)
    val mouth = drawRiverBody(pts, unitScale, animMs)
    drawRiverWoods(mouth, unitScale)
}

/** Grid-space (col, row) waypoints for the river's centerline, packed into an
 * [Offset] purely as a (x=col, y=row) float pair — not a screen coordinate.
 * Starts [alongFraction] of the way along [side]'s edge, bows gently outward,
 * and ends at that edge's far corner where the woods/pool sit (see
 * [drawRiverWoods]) — a fixed small perpendicular distance outside the border,
 * tight enough to read as bordering the map rather than floating off it,
 * regardless of which edge. Local (along-edge, perpendicular) coordinates are
 * computed once and then mapped onto actual (col, row) per [side], so all 4
 * edges share the exact same curve shape, just rotated/mirrored. */
private fun riverPathGrid(cols: Int, rows: Int, side: DecorationSide, alongFraction: Float): List<Offset> {
    val axisLen = when (side) {
        DecorationSide.TOP, DecorationSide.BOTTOM -> cols
        DecorationSide.LEFT, DecorationSide.RIGHT -> rows
    }
    val startU = alongFraction.coerceIn(0f, 1f)
    val steps = 14
    return (0..steps).map { i ->
        val t = i / steps.toFloat()
        val u = startU + (1f - startU) * t
        val along = u * (axisLen - 1)
        val bow = kotlin.math.sin(t * Math.PI.toFloat()) * 0.55f
        val perp = -1.25f - bow - t * 0.25f
        when (side) {
            DecorationSide.TOP -> Offset(along, perp)
            DecorationSide.BOTTOM -> Offset(along, (rows - 1) - perp)
            DecorationSide.LEFT -> Offset(perp, along)
            DecorationSide.RIGHT -> Offset((cols - 1) - perp, along)
        }
    }
}

/**
 * Cave & bear backdrop (founder-approved design, docs/previews/cave-bear-preview.html):
 * a rocky den with a dark arched entrance; a bear periodically rises up out of
 * the dark to look around, then retreats back inside. Position comes from
 * [side]/[alongFraction] exactly like the river (see DecorationEntity), so it
 * stays outside the fence and in the same relative spot through every map
 * expansion. The peek timing is fully stateless/kill-safe: [decorationId]
 * deterministically picks a fixed hidden-duration between 1 and 5 real minutes
 * (see caveHiddenDurationMs) and the phase is recomputed every frame purely
 * from elapsed wall-clock time modulo that cycle — nothing to lose if the app
 * is backgrounded mid-peek, same idea as CowHunger/AnimalLifespan's timestamp
 * pattern, just with no persisted state at all since the "random" pick only
 * needs to be stable, not re-rolled.
 */
private fun DrawScope.drawCaveAndBear(
    originOffset: Offset,
    canvasCenter: Offset,
    scale: Float,
    pan: Offset,
    tileW: Float,
    tileH: Float,
    gridConfig: GridConfig,
    side: DecorationSide,
    alongFraction: Float,
    decorationId: Int
) {
    fun toScreen(col: Float, row: Float): Offset {
        val bx = originOffset.x + GridMath.isoX(col, row, tileW)
        val by = originOffset.y + GridMath.isoY(col, row, tileH)
        return Offset(
            (bx - canvasCenter.x) * scale + canvasCenter.x + pan.x,
            (by - canvasCenter.y) * scale + canvasCenter.y + pan.y
        )
    }

    val anchorGrid = caveAnchorGrid(side, alongFraction, gridConfig.cols, gridConfig.rows)
    val anchor = toScreen(anchorGrid.x, anchorGrid.y)
    // Preview art (docs/previews/cave-bear-preview.html) was tuned against a
    // 112px reference tile width — same "1 tile-width = 1 unit" convention the
    // fence/cow/river already use.
    val unitScale = (tileW * scale) / 112f
    fun u(v: Float) = v * unitScale

    val mouth = drawCaveMound(anchor, unitScale)

    val hiddenMs = caveHiddenDurationMs(decorationId)
    val riseMs = 500L
    val holdMs = 1800L
    val sinkMs = 500L
    val cycleMs = hiddenMs + riseMs + holdMs + sinkMs
    val local = System.currentTimeMillis().mod(cycleMs)
    val riseEnd = hiddenMs + riseMs
    val holdEnd = riseEnd + holdMs
    val p: Float
    val holdT: Float
    when {
        local < hiddenMs -> {
            p = 0f; holdT = 0f
        }
        local < riseEnd -> {
            p = easeOutBack((local - hiddenMs) / riseMs.toFloat()).coerceIn(0f, 1f); holdT = 0f
        }
        local < holdEnd -> {
            p = 1f; holdT = (local - riseEnd) / 1000f
        }
        else -> {
            p = (1f - easeInCubic((local - holdEnd) / sinkMs.toFloat())).coerceIn(0f, 1f); holdT = 0f
        }
    }

    clipPath(mouth.clip) {
        val hiddenY = mouth.floorY + u(46f)
        val peekY = mouth.topY + mouth.mouthW * 0.42f
        val headY = hiddenY + (peekY - hiddenY) * p
        if (p > 0.05f) drawBearHead(Offset(mouth.mouthCx, headY), unitScale, holdT)
        if (local in (riseEnd + 250)..holdEnd) {
            drawBearPaws(Offset(mouth.mouthCx, mouth.floorY - u(6f)), unitScale, u(21f))
        }
    }
}

/** Deterministic per-decoration "random" pick of the bear's hidden duration,
 * uniformly in [1, 5] real minutes — a fixed hash of [decorationId] rather
 * than re-rolled/persisted state, so it's stable across recompositions and
 * process death with nothing to save or restore. */
private fun caveHiddenDurationMs(decorationId: Int): Long {
    val hash = Math.floorMod(decorationId.toLong() * 2654435761L, 240_000L)
    return 60_000L + hash
}

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val m = t - 1f
    return 1f + c3 * (m * m * m) + c1 * (m * m)
}

private fun easeInCubic(t: Float): Float = t * t * t

/** Same relative-placement shape as the river: [side] + how far [alongFraction]
 * along it (0..1), with a fixed small perpendicular distance outside the
 * border. Unlike the river's path, the cave is a single point, so there's no
 * bow/fade interpolation — just the side-to-axis mapping. */
private fun caveAnchorGrid(side: DecorationSide, alongFraction: Float, cols: Int, rows: Int): Offset {
    val perp = -1.5f
    val f = alongFraction.coerceIn(0f, 1f)
    val alongCols = f * (cols - 1)
    val alongRows = f * (rows - 1)
    return when (side) {
        DecorationSide.TOP -> Offset(alongCols, perp)
        DecorationSide.BOTTOM -> Offset(alongCols, (rows - 1) - perp)
        DecorationSide.LEFT -> Offset(perp, alongRows)
        DecorationSide.RIGHT -> Offset((cols - 1) - perp, alongRows)
    }
}

private data class CaveMouthGeometry(val mouthCx: Float, val topY: Float, val mouthW: Float, val floorY: Float, val clip: Path)

/** Rocky den mound: overlapping earth/rock domes (same "flat shape + ink
 * outline" grammar as every other asset) with a dark arched entrance cut into
 * the lower-front face, flanked by two doorpost boulders (reusing
 * [drawRiverRock] as-is) and capped with a few grass tufts (reusing
 * [drawRiverReed]'s blade-scatter technique). [anchor] is the mound's
 * ground-level floor-center point (same "diamond center" convention every
 * other tile element uses). Always drawn in one fixed frontal orientation
 * regardless of which border edge it's placed on — like the barn mesh, only
 * its anchor point moves, it never rotates. */
private fun DrawScope.drawCaveMound(anchor: Offset, unitScale: Float): CaveMouthGeometry {
    fun u(v: Float) = v * unitScale
    val x = anchor.x
    val y = anchor.y

    drawSoftGroundShadow(Offset(x, y + u(8f)), u(150f) * 0.55f, u(92f) * 0.3f)

    val blobs = listOf(
        Triple(-46f, -10f, 46f) to Color(0xFF7C6A52),
        Triple(40f, -6f, 50f) to Color(0xFF6D5C46),
        Triple(-6f, -34f, 58f) to Color(0xFF8A7860),
        Triple(4f, -8f, 40f) to Color(0xFF6D5638)
    )
    for ((geo, color) in blobs) {
        val (dx, dy, r) = geo
        val rx = u(r)
        val ry = u(r) * 0.82f
        drawOval(
            color = color,
            topLeft = Offset(x + u(dx) - rx, y + u(dy) - ry),
            size = Size(rx * 2f, ry * 2f)
        )
    }

    val moundW = 150f
    val moundH = 92f
    val silhouette = Path().apply {
        moveTo(x - u(moundW / 2f), y + u(6f))
        quadraticTo(x - u(moundW / 2f), y - u(moundH), x, y - u(moundH + 6f))
        quadraticTo(x + u(moundW / 2f), y - u(moundH), x + u(moundW / 2f), y + u(6f))
    }
    drawPath(silhouette, color = Color(0xFF3A332B), style = Stroke(width = u(2.2f)))

    val mouthW = 58f
    val mx = x + u(6f)
    val myTop = y + u(-70f)
    val archCenterY = myTop + u(mouthW / 2f)
    val archRadius = u(mouthW / 2f)
    val clip = Path().apply {
        moveTo(mx - archRadius, y)
        lineTo(mx - archRadius, archCenterY)
        arcTo(
            rect = Rect(
                left = mx - archRadius,
                top = archCenterY - archRadius,
                right = mx + archRadius,
                bottom = archCenterY + archRadius
            ),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false
        )
        lineTo(mx + archRadius, y)
        close()
    }
    val mouthGradient = Brush.linearGradient(
        colorStops = arrayOf(0f to Color(0xFF0C0906), 1f to Color(0xFF241C14)),
        start = Offset(mx, myTop),
        end = Offset(mx, y)
    )
    drawPath(clip, brush = mouthGradient)
    drawPath(clip, color = Color(0xFF4A3A26), style = Stroke(width = u(5f)))
    drawPath(clip, color = Color(0xFF3A332B), style = Stroke(width = u(2f)))

    drawRiverRock(Offset(mx - archRadius - u(14f), y - u(6f)), u(20f), -0.25f)
    drawRiverRock(Offset(mx + archRadius + u(12f), y - u(10f)), u(22f), 0.3f)
    drawRiverRock(Offset(x - u(moundW / 2f) + u(10f), y + u(4f)), u(12f), 0.6f)

    val grassPalette = listOf(Color(0xFF558B2F), Color(0xFF689F38), Color(0xFF43A047))
    drawRiverReed(Offset(x - u(44f), y - u(moundH) + u(22f)), unitScale, grassPalette)
    drawRiverReed(Offset(x + u(4f), y - u(moundH) - u(2f)), unitScale * 1.1f, grassPalette)
    drawRiverReed(Offset(x + u(50f), y - u(moundH) + u(26f)), unitScale * 0.9f, grassPalette)

    return CaveMouthGeometry(mouthCx = mx, topY = myTop, mouthW = archRadius * 2f, floorY = y, clip = clip)
}

/** Chibi bear head — same proportions/ink language as [drawWanderingCow]'s
 * head but in warm bear-brown so she never reads as "the cow again". [anchor]
 * is the head's center; [lookT] (seconds into the "peeking" hold, 0 outside
 * that phase) drives a gentle side-to-side look and an occasional blink. */
private fun DrawScope.drawBearHead(anchor: Offset, unitScale: Float, lookT: Float) {
    fun u(v: Float) = v * unitScale
    val fur = Color(0xFF7A5230)
    val furDark = Color(0xFF5F3F23)
    val snout = Color(0xFFD9B48F)
    val ink = Color(0xFF3A332B)

    val wobble = kotlin.math.sin(lookT * 1.6f) * u(7f)
    val cx = anchor.x + wobble * 0.3f
    val cy = anchor.y

    for (ex in floatArrayOf(-19f, 19f)) {
        val center = Offset(cx + u(ex), cy - u(24f))
        drawOval(color = fur, topLeft = center - Offset(u(11f), u(11f)), size = Size(u(22f), u(22f)))
        drawOval(color = ink, topLeft = center - Offset(u(11f), u(11f)), size = Size(u(22f), u(22f)), style = Stroke(u(2f)))
        drawOval(color = furDark, topLeft = center - Offset(u(5.5f), u(5.5f)), size = Size(u(11f), u(11f)))
    }

    drawOval(color = fur, topLeft = Offset(cx - u(30f), cy - u(28f)), size = Size(u(60f), u(56f)))
    drawOval(color = ink, topLeft = Offset(cx - u(30f), cy - u(28f)), size = Size(u(60f), u(56f)), style = Stroke(u(2.4f)))

    val snoutCenter = Offset(cx, cy + u(12f))
    drawOval(color = snout, topLeft = snoutCenter - Offset(u(15f), u(11f)), size = Size(u(30f), u(22f)))
    drawOval(color = ink, topLeft = snoutCenter - Offset(u(15f), u(11f)), size = Size(u(30f), u(22f)), style = Stroke(u(1.8f)))

    drawOval(color = ink, topLeft = Offset(cx - u(4.5f), cy + u(6f) - u(3.4f)), size = Size(u(9f), u(6.8f)))

    val blinking = kotlin.math.max(0f, kotlin.math.sin(lookT * 3.1f)) < 0.06f
    val blink = if (blinking) 0.15f else 1f
    for (ex in floatArrayOf(-11f, 11f)) {
        drawOval(
            color = ink,
            topLeft = Offset(cx + u(ex) - u(3.1f), cy - u(4f) - u(3.1f) * blink),
            size = Size(u(6.2f), u(6.2f) * blink)
        )
    }
}

private fun DrawScope.drawBearPaws(anchor: Offset, unitScale: Float, spread: Float) {
    val fur = Color(0xFF7A5230)
    val ink = Color(0xFF3A332B)
    for (dx in floatArrayOf(-spread, spread)) {
        val center = Offset(anchor.x + dx, anchor.y)
        drawOval(color = fur, topLeft = center - Offset(unitScale * 9f, unitScale * 6f), size = Size(unitScale * 18f, unitScale * 12f))
        drawOval(color = ink, topLeft = center - Offset(unitScale * 9f, unitScale * 6f), size = Size(unitScale * 18f, unitScale * 12f), style = Stroke(unitScale * 1.8f))
    }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** River half-width in preview units (before [unitScale]) at arc-fraction [u]:
 * fades in from zero at the spring, settles into a gently undulating channel,
 * then bulges into the mouth's pool near the end. */
private fun riverWidthUnits(u: Float): Float {
    val base = 13f + 5f * kotlin.math.sin(u * 2.6f + 0.4f)
    val sourceFade = smoothstep(0f, 0.14f, u)
    val poolBulge = smoothstep(0.78f, 1f, u) * 20f
    return (base + poolBulge) * sourceFade
}

private fun Float.fract(): Float = this - kotlin.math.floor(this)

/** Offsets a screen-space polyline into parallel left/right edges by a
 * per-point width (perpendicular to the local tangent) — the same technique
 * the river-woods preview's `offsetRibbon` uses, ported 1:1. */
private fun offsetRibbon(points: List<Offset>, widthFn: (Float) -> Float): Pair<List<Offset>, List<Offset>> {
    val left = ArrayList<Offset>(points.size)
    val right = ArrayList<Offset>(points.size)
    for (i in points.indices) {
        val prev = points[max(0, i - 1)]
        val next = points[min(points.size - 1, i + 1)]
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val len = hypot(dx, dy).let { if (it == 0f) 1f else it }
        val nx = -dy / len
        val ny = dx / len
        val w = widthFn(i / (points.size - 1).toFloat())
        left.add(Offset(points[i].x + nx * w, points[i].y + ny * w))
        right.add(Offset(points[i].x - nx * w, points[i].y - ny * w))
    }
    return left to right
}

/** Soft dark radial-falloff contact shadow — the same shape/gradient the barn
 * draw-lambda above uses inline to ground the mesh on the dirt, reused here so
 * the river's rocks/pool and every tree read as sitting on the ground rather
 * than pasted over it. */
private fun DrawScope.drawSoftGroundShadow(center: Offset, rx: Float, ry: Float) {
    if (rx <= 0f || ry <= 0f) return
    drawOval(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color(0x66231A0F),
                0.7f to Color(0x33231A0F),
                1f to Color(0x00231A0F)
            ),
            center = center,
            radius = rx
        ),
        topLeft = Offset(center.x - rx, center.y - ry),
        size = Size(rx * 2f, ry * 2f)
    )
}

/** A single angular rock, ink-outlined like every other shape in this file's
 * "programmatic 2D" family — used both to frame the spring and to ring the
 * pool at the river's mouth. */
private fun DrawScope.drawRiverRock(center: Offset, r: Float, rotationRad: Float) {
    if (r <= 0f) return
    rotate(degrees = Math.toDegrees(rotationRad.toDouble()).toFloat(), pivot = center) {
        val body = Path().apply {
            moveTo(center.x - r, center.y + r * 0.3f)
            lineTo(center.x - r * 0.5f, center.y - r * 0.7f)
            lineTo(center.x + r * 0.3f, center.y - r)
            lineTo(center.x + r, center.y - r * 0.1f)
            lineTo(center.x + r * 0.6f, center.y + r * 0.6f)
            lineTo(center.x - r * 0.2f, center.y + r * 0.8f)
            close()
        }
        drawPath(body, color = Color(0xFF8F8477))
        val highlight = Path().apply {
            moveTo(center.x - r * 0.5f, center.y - r * 0.7f)
            lineTo(center.x + r * 0.3f, center.y - r)
            lineTo(center.x + r * 0.1f, center.y - r * 0.2f)
            close()
        }
        drawPath(highlight, color = Color(0x2EFFFFFF))
        drawPath(body, color = Color(0xFF3A332B), style = Stroke(width = 1.6f))
    }
}

/** A three-blade reed tuft along the riverbank, using the same seeded-scatter
 * "blade stroke" grammar as [drawWheatTile]/[drawCarrotTile]'s crop blades. */
private fun DrawScope.drawRiverReed(anchor: Offset, unitScale: Float, palette: List<Color>) {
    fun u(v: Float) = v * unitScale
    val blades = listOf(Triple(-3f, 14f, -3f), Triple(0f, 18f, 1f), Triple(3f, 12f, 4f))
    blades.forEachIndexed { i, (dx, h, tilt) ->
        val path = Path().apply {
            moveTo(anchor.x + u(dx), anchor.y)
            quadraticTo(
                anchor.x + u(dx + tilt), anchor.y - u(h * 0.6f),
                anchor.x + u(dx + tilt * 1.6f), anchor.y - u(h)
            )
        }
        drawPath(path, color = palette[i % palette.size], style = Stroke(width = u(2f), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawMistPuff(center: Offset, r: Float, alpha: Float) {
    if (r <= 0f || alpha <= 0f) return
    drawOval(
        color = Color(0xFFFFFAF0).copy(alpha = alpha.coerceIn(0f, 1f)),
        topLeft = Offset(center.x - r, center.y - r * 0.65f),
        size = Size(r * 2f, r * 1.3f)
    )
}

/** The river's source: a small cluster of ink-outlined rocks (same brown
 * family as the fence posts) framing a dark shadowed gap the water "emerges"
 * from, plus a few soft mist puffs drifting up and fading — reads as water
 * bubbling out of the terrain rather than a shape starting in blank space. */
private fun DrawScope.drawRiverSpring(origin: Offset, unitScale: Float, animMs: Float) {
    fun u(v: Float) = v * unitScale
    drawSoftGroundShadow(Offset(origin.x, origin.y + u(4f)), u(30f), u(14f))

    val gapR = u(10f)
    drawOval(
        color = Color(0x8C231A0F),
        topLeft = Offset(origin.x + u(2f) - gapR, origin.y - u(2f) - gapR * 0.6f),
        size = Size(gapR * 2f, gapR * 1.2f)
    )
    drawRiverRock(Offset(origin.x - u(16f), origin.y - u(6f)), u(12f), -0.3f)
    drawRiverRock(Offset(origin.x + u(10f), origin.y - u(12f)), u(15f), 0.4f)
    drawRiverRock(Offset(origin.x + u(20f), origin.y + u(6f)), u(10f), 0.9f)

    val animSec = animMs / 1000f
    for (k in 0 until 3) {
        val phase = animSec * 0.6f + k * 2.1f
        val riseRaw = phase * 6f
        val rise = riseRaw - 26f * kotlin.math.floor(riseRaw / 26f)
        val alpha = 0.35f * (1f - rise / 26f)
        drawMistPuff(
            Offset(origin.x - u(4f) + u(kotlin.math.sin(phase) * 6f), origin.y - u(14f) - u(rise)),
            u(9f - rise * 0.15f),
            alpha
        )
    }
}

/** The river channel itself: reed-lined banks, a fading-in-then-flowing water
 * body with animated highlight glints + sparkles, and a small lily-pad pool
 * ringed with rocks at the mouth. Returns the mouth point so [drawRiverWoods]
 * can anchor the tree cluster directly off it. */
private fun DrawScope.drawRiverBody(pts: List<Offset>, unitScale: Float, animMs: Float): Offset {
    val (left, right) = offsetRibbon(pts) { u -> riverWidthUnits(u) * unitScale }

    val reedPalette = listOf(Color(0xFF558B2F), Color(0xFF689F38), Color(0xFF2E7D32), Color(0xFF43A047))
    listOf(0.3f, 0.48f, 0.62f, 0.72f).forEachIndexed { i, uFrac ->
        val idx = (uFrac * (pts.size - 1)).roundToInt().coerceIn(0, pts.size - 1)
        val side = if (i % 2 == 0) left else right
        drawRiverReed(side[idx], unitScale, reedPalette)
    }

    val waterPath = Path().apply {
        moveTo(left.first().x, left.first().y)
        left.forEach { lineTo(it.x, it.y) }
        for (i in right.indices.reversed()) lineTo(right[i].x, right[i].y)
        close()
    }
    drawPath(
        waterPath,
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color(0x005FB3D9),
                0.18f to Color(0xFF6FC2E6),
                1f to Color(0xFF3F8FC4)
            ),
            start = pts.first(),
            end = pts.last()
        )
    )
    drawPath(waterPath, color = Color(0x8C3A332B), style = Stroke(width = 1.6f))

    clipPath(waterPath) {
        val animSec = animMs / 1000f
        for (k in 0 until 4) {
            val u = 0.16f + (animSec * 0.22f + k / 4f).fract() * 0.84f
            val idx = (u * (pts.size - 1)).toInt().coerceIn(0, pts.size - 1)
            val p = pts[idx]
            val alpha = (0.5f * kotlin.math.sin(((u - 0.16f) / 0.84f) * Math.PI.toFloat()) + 0.12f).coerceIn(0f, 1f)
            rotate(degrees = 23f, pivot = p) {
                drawOval(
                    color = Color.White.copy(alpha = alpha),
                    topLeft = Offset(p.x - unitScale * 11f, p.y - unitScale * 4.5f),
                    size = Size(unitScale * 22f, unitScale * 9f)
                )
            }
        }
        // Fixed seed (matches the preview's mulberry32(7)) — only the sparkles'
        // position-along-path advances with animSec, their scatter pattern
        // itself stays stable frame to frame.
        val rnd = Random(7)
        for (k in 0 until 10) {
            val u = 0.16f + (animSec * 0.4f + rnd.nextFloat()).fract() * 0.84f
            val idx = (u * (pts.size - 1)).toInt().coerceIn(0, pts.size - 1)
            val p = pts[idx]
            val jitter = (rnd.nextFloat() - 0.5f) * unitScale * 12f
            val sparkle = (kotlin.math.sin(animSec * 6f + k) + 1f) / 2f
            drawCircle(
                color = Color.White.copy(alpha = (0.3f + sparkle * 0.5f).coerceIn(0f, 1f)),
                radius = unitScale * 1.5f,
                center = Offset(p.x + jitter, p.y + jitter * 0.4f)
            )
        }
    }

    val mouth = pts.last()
    drawRiverRock(Offset(mouth.x - unitScale * 24f, mouth.y + unitScale * 6f), unitScale * 9f, -0.5f)
    drawRiverRock(Offset(mouth.x + unitScale * 14f, mouth.y - unitScale * 16f), unitScale * 8f, 0.2f)
    for ((dx, dy) in listOf(-8f to 6f, 10f to -4f, -2f to -14f)) {
        val cx = mouth.x + unitScale * dx
        val cy = mouth.y + unitScale * dy
        val rx = unitScale * 6f
        val ry = unitScale * 3.6f
        drawOval(color = Color(0xFF2E7D32), topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f))
        drawOval(color = Color(0xFF3A332B), topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f), style = Stroke(width = unitScale * 1f))
    }

    return mouth
}

/** Woods cluster anchored directly off the river's own mouth point (rather
 * than an independent grid position) so the primary tree's canopy overlaps
 * the pool edge and the river visibly flows into the woods. */
private fun DrawScope.drawRiverWoods(mouth: Offset, unitScale: Float) {
    val palA = listOf(Color(0xFF2E7D32), Color(0xFF1B5E20), Color(0xFF43A047), Color(0xFF1B5E20))
    val palB = listOf(Color(0xFF558B2F), Color(0xFF33691E), Color(0xFF689F38), Color(0xFF33691E))
    data class TreeSpec(val dx: Float, val dy: Float, val s: Float, val pal: List<Color>)
    val positions = listOf(
        TreeSpec(22f, -20f, 1.15f, palA),
        TreeSpec(-14f, -34f, 0.85f, palB),
        TreeSpec(40f, -6f, 0.9f, palA),
        TreeSpec(8f, -46f, 0.7f, palB),
        TreeSpec(54f, -28f, 0.75f, palA),
        TreeSpec(-2f, -58f, 0.8f, palB)
    )
    for (spec in positions) {
        drawRiverTree(
            Offset(mouth.x + unitScale * spec.dx, mouth.y + unitScale * spec.dy),
            unitScale * spec.s,
            spec.pal
        )
    }
}

/** A single tree: trunk + 4 lobed, ink-outlined canopy blobs (same rounded
 * language as [drawWanderingCow]'s body/head ovals), a warm highlight fleck
 * matching the cow's snout/eye highlights, and a ground-contact shadow. */
private fun DrawScope.drawRiverTree(anchor: Offset, scale: Float, palette: List<Color>) {
    drawSoftGroundShadow(Offset(anchor.x, anchor.y + scale * 6f), scale * 22f, scale * 10f)

    drawLine(
        color = Color(0xFF6D5638),
        start = Offset(anchor.x, anchor.y + scale * 8f),
        end = Offset(anchor.x, anchor.y - scale * 2f),
        strokeWidth = scale * 4f,
        cap = StrokeCap.Round
    )

    val blobs = listOf(Triple(0f, -18f, 15f), Triple(-11f, -10f, 11f), Triple(11f, -9f, 11f), Triple(0f, -6f, 13f))
    blobs.forEachIndexed { i, (dx, dy, r) ->
        val cx = anchor.x + dx * scale
        val cy = anchor.y + dy * scale
        val rx = r * scale
        val ry = r * 0.85f * scale
        drawOval(color = palette[i % palette.size], topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f))
        drawOval(color = Color(0xFF3A332B), topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f), style = Stroke(width = scale * 1.6f))
    }

    val hlCenter = Offset(anchor.x - scale * 6f, anchor.y - scale * 22f)
    rotate(degrees = -23f, pivot = hlCenter) {
        drawOval(
            color = Color(0xFFFFFAF0).copy(alpha = 0.55f),
            topLeft = Offset(hlCenter.x - scale * 4f, hlCenter.y - scale * 2.6f),
            size = Size(scale * 8f, scale * 5.2f)
        )
    }
}

/**
 * Resolves a floating (col, row) tap position — recovered by inverting the
 * isometric projection, so it's valid anywhere, including well outside the
 * cols x rows array — into a border side + fraction-along-that-side, for
 * decoration placement (founder request 2026-08-18). Returns null if the tap
 * landed inside the fence (i.e. isn't actually outside the buildable area),
 * so the caller can just ignore it and let the player try again. When the tap
 * is outside on more than one axis at once (e.g. a corner, past both a
 * col and a row edge), the axis with the larger excursion wins — it reads as
 * "which edge the player was clearly aiming for".
 */
private fun resolveDecorationTarget(col: Float, row: Float, cols: Int, rows: Int): Pair<DecorationSide, Float>? {
    val overTop = -row
    val overBottom = row - (rows - 1)
    val overLeft = -col
    val overRight = col - (cols - 1)

    val best = listOf(
        DecorationSide.TOP to overTop,
        DecorationSide.BOTTOM to overBottom,
        DecorationSide.LEFT to overLeft,
        DecorationSide.RIGHT to overRight
    ).maxByOrNull { it.second } ?: return null

    if (best.second <= 0f) return null // tap landed inside the fence — not a valid placement spot

    val fraction = when (best.first) {
        DecorationSide.TOP, DecorationSide.BOTTOM -> col / (cols - 1)
        DecorationSide.LEFT, DecorationSide.RIGHT -> row / (rows - 1)
    }
    return best.first to fraction.coerceIn(0f, 1f)
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
