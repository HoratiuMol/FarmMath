package com.farmmathbuilder.app.data.repository

import com.farmmathbuilder.app.data.dao.CellDao
import com.farmmathbuilder.app.data.dao.PlayerDao
import com.farmmathbuilder.app.data.dao.SettingsDao
import com.farmmathbuilder.app.data.entity.CellEntity
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.domain.GridConfig
import com.farmmathbuilder.app.domain.GridMath
import com.farmmathbuilder.app.domain.GrowthCalculator
import com.farmmathbuilder.app.domain.GrowthPhase
import com.farmmathbuilder.app.domain.OccupantType
import com.farmmathbuilder.app.domain.isCrop
import com.farmmathbuilder.app.domain.PathType
import com.farmmathbuilder.app.domain.SlotAvailability
import com.farmmathbuilder.app.domain.TextSizeOption
import com.farmmathbuilder.app.domain.UiCell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import java.util.Calendar

/**
 * Single source of truth for game state. Exposes combined StateFlow-friendly data
 * to ViewModels and performs all mutating actions as immediate Room writes (each
 * Room transaction is durable, satisfying the "auto-save on every mutating action"
 * requirement (FR-072/FR-073) for free — no separate 5-minute timer needed to avoid
 * data loss, though we still expose an explicit persistNow() no-op-safe hook for
 * lifecycle onPause/onStop calls).
 */
class FarmRepository(
    private val cellDao: CellDao,
    private val playerDao: PlayerDao,
    private val settingsDao: SettingsDao
) {

    val cells: Flow<List<CellEntity>> = cellDao.observeAll()
    val player: Flow<PlayerEntity?> = playerDao.observe()
    val settings: Flow<SettingsEntity?> = settingsDao.observe()

    /**
     * Testing aid: the player's wheat currency is floored at [TEST_MIN_WHEAT]
     * on every write, so there's always enough to freely test paid actions
     * (e.g. expandGrid) without grinding harvests first. Remove this floor
     * (and the ensurePlayerInitialized seed value below) once real economy
     * balancing/testing is no longer needed.
     */
    private suspend fun updatePlayer(p: PlayerEntity) =
        playerDao.update(p.copy(wheatCurrency = maxOf(p.wheatCurrency, TEST_MIN_WHEAT)))

    val uiCells: Flow<List<UiCell>> = cells.combine(player) { cellList, p ->
        val config = configFor(p)
        cellList.map { it.toUiCell(config = config) }
    }

    /** Resolves a player's persisted grid state into a [GridConfig], falling back to
     * the default (centered) building anchor when the player hasn't moved it yet. */
    private fun configFor(p: PlayerEntity?): GridConfig {
        if (p == null) return GridConfig.BASE
        return GridConfig(
            cols = p.gridCols,
            rows = p.gridRows,
            buildableRadius = p.buildableRadius,
            buildingAnchorCol = p.buildingAnchorCol ?: GridMath.defaultBuildingAnchorCol(p.gridCols),
            buildingAnchorRow = p.buildingAnchorRow ?: GridMath.defaultBuildingAnchorRow(p.gridRows)
        )
    }

    private fun CellEntity.toUiCell(now: Long = System.currentTimeMillis(), config: GridConfig): UiCell {
        val phase = if (occupantType.isCrop()) {
            GrowthCalculator.computePhase(plantedAtTimestamp, growthDurationMs, now)
        } else GrowthPhase.NONE
        val progress = if (occupantType.isCrop()) {
            GrowthCalculator.computeProgress(plantedAtTimestamp, growthDurationMs, now)
        } else 0f
        return UiCell(
            id = id,
            col = col,
            row = row,
            occupantType = occupantType,
            growthPhase = phase,
            growthProgress = progress,
            plantedAtTimestamp = plantedAtTimestamp,
            growthDurationMs = growthDurationMs,
            pathType = pathType,
            pathRotationDegrees = pathRotationDegrees,
            isBuildingCell = GridMath.isBuildingCell(col, row, config.buildingAnchorCol, config.buildingAnchorRow),
            isWithinBuildableRadius = GridMath.isWithinBuildableRadius(col, row, config.cols, config.rows, config.buildableRadius, config.buildingAnchorCol, config.buildingAnchorRow)
        )
    }

    /** Creates the starting BASE_COLS x BASE_ROWS grid with the central 2x2 Farm Building. */
    suspend fun ensureGridInitialized() {
        if (cellDao.count() > 0) return
        val cols = GridMath.BASE_COLS
        val rows = GridMath.BASE_ROWS
        val anchorCol = GridMath.defaultBuildingAnchorCol(cols)
        val anchorRow = GridMath.defaultBuildingAnchorRow(rows)
        val cells = mutableListOf<CellEntity>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val occupant = if (GridMath.isBuildingCell(col, row, anchorCol, anchorRow)) OccupantType.BUILDING else OccupantType.EMPTY
                cells.add(
                    CellEntity(
                        id = GridMath.cellId(col, row, cols),
                        col = col,
                        row = row,
                        occupantType = occupant
                    )
                )
            }
        }
        cellDao.insertAll(cells)
    }

    suspend fun ensurePlayerInitialized() {
        val existing = playerDao.get()
        if (existing == null) {
            playerDao.insert(
                PlayerEntity(
                    lastDailyResetTimestamp = System.currentTimeMillis(),
                    wheatCurrency = TEST_MIN_WHEAT,
                    // Cow starts "just fed" so a new save doesn't open with the
                    // hunger icon already showing — see CowHunger.
                    cowLastFedTimestamp = System.currentTimeMillis()
                )
            )
        } else if (existing.wheatCurrency < TEST_MIN_WHEAT) {
            // Top up saves from before the test-wheat floor existed (or any save
            // that predates it) — updatePlayer() only floors on the *next*
            // mutating write, so an untouched existing save would otherwise sit
            // at its old wheatCurrency (e.g. 0) until the player harvests/plants.
            updatePlayer(existing)
        }
    }

    suspend fun ensureSettingsInitialized() {
        if (settingsDao.get() == null) {
            settingsDao.insert(SettingsEntity())
        }
    }

    suspend fun initializeAll() {
        ensureGridInitialized()
        ensurePlayerInitialized()
        ensureSettingsInitialized()
    }

    /**
     * R-5: daily reset keyed to device-local midnight, not UTC. Returns true if a
     * reset actually happened (caller can surface the "5 new fields available" toast).
     */
    suspend fun checkAndApplyDailyReset(): Boolean {
        val current = playerDao.get() ?: return false
        val now = System.currentTimeMillis()
        if (isSameLocalDay(current.lastDailyResetTimestamp, now)) return false

        updatePlayer(
            current.copy(
                freeFieldsUsedToday = 0,
                extraFieldsEarnedToday = 0,
                extraFieldsUsedToday = 0,
                lastDailyResetTimestamp = now
            )
        )
        return true
    }

    private fun isSameLocalDay(a: Long, b: Long): Boolean {
        if (a <= 0L) return false
        val calA = Calendar.getInstance().apply { timeInMillis = a }
        val calB = Calendar.getInstance().apply { timeInMillis = b }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    fun slotAvailability(p: PlayerEntity): SlotAvailability {
        return when {
            p.freeFieldsUsedToday < 5 -> SlotAvailability.FREE_SLOT_AVAILABLE
            p.extraFieldsEarnedToday > p.extraFieldsUsedToday -> SlotAvailability.EXTRA_SLOT_AVAILABLE
            else -> SlotAvailability.NONE_AVAILABLE
        }
    }

    /** Carrot is a second crop, meant to unlock once the player has harvested
     * [CARROT_UNLOCK_WHEAT_HARVESTS] *wheat* fields specifically (not total crop
     * harvests — [PlayerEntity.wheatHarvestedTotal] only increments for wheat, see
     * [harvest]/[harvestAll]).
     *
     * Testing aid: unlocked from the start regardless of harvests, same "always
     * testable" convention as [TEST_MIN_WHEAT]. Restore the real gate (commented
     * below) once carrot's unlock pacing doesn't need to be freely testable.
     */
    fun isCarrotUnlocked(p: PlayerEntity): Boolean = true
    // Real gate: p.wheatHarvestedTotal >= CARROT_UNLOCK_WHEAT_HARVESTS

    /** Plants a crop (wheat or carrot) into an empty cell, consuming a free or extra
     * slot per the decision table. Both crops currently share the same growth
     * duration/reward — see FarmGridCanvas.drawCarrotTile's doc for the visual side. */
    suspend fun plantCrop(cellId: Int, cropType: OccupantType): Boolean {
        if (!cropType.isCrop()) return false
        val cell = cellDao.getById(cellId) ?: return false
        if (cell.occupantType != OccupantType.EMPTY) return false
        val p = playerDao.get() ?: return false
        if (cropType == OccupantType.CARROT && !isCarrotUnlocked(p)) return false
        val availability = slotAvailability(p)
        if (availability == SlotAvailability.NONE_AVAILABLE) return false

        val slotType = if (availability == SlotAvailability.FREE_SLOT_AVAILABLE) "FREE" else "EXTRA"

        cellDao.update(
            cell.copy(
                occupantType = cropType,
                plantedAtTimestamp = System.currentTimeMillis(),
                growthDurationMs = GrowthCalculator.NORMAL_GROWTH_DURATION_MS,
                consumedSlotType = slotType
            )
        )

        updatePlayer(
            p.copy(
                freeFieldsUsedToday = if (slotType == "FREE") p.freeFieldsUsedToday + 1 else p.freeFieldsUsedToday,
                extraFieldsUsedToday = if (slotType == "EXTRA") p.extraFieldsUsedToday + 1 else p.extraFieldsUsedToday
            )
        )
        return true
    }

    /**
     * R-4: cancelling a growing (non-mature) field restores the daily slot it
     * consumed (free or extra), consistent with BR-007's "no cost to destroy/rebuild".
     */
    suspend fun cancelGrowth(cellId: Int): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (!cell.occupantType.isCrop()) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase == GrowthPhase.MATURE || phase == GrowthPhase.NONE) return false

        val p = playerDao.get() ?: return false
        val restoredPlayer = when (cell.consumedSlotType) {
            "FREE" -> p.copy(freeFieldsUsedToday = (p.freeFieldsUsedToday - 1).coerceAtLeast(0))
            "EXTRA" -> p.copy(extraFieldsUsedToday = (p.extraFieldsUsedToday - 1).coerceAtLeast(0))
            else -> p
        }
        updatePlayer(restoredPlayer)

        cellDao.update(
            cell.copy(
                occupantType = OccupantType.EMPTY,
                plantedAtTimestamp = 0L,
                growthDurationMs = 0L,
                consumedSlotType = null
            )
        )
        return true
    }

    /** FR-015: harvest a mature crop, cell returns to empty. Wheat pays +5 wheat
     * currency (BR-003); carrot pays no currency at all — its entire purpose is
     * becoming cow feed, so it instead adds +1 to [PlayerEntity.carrotInventory]
     * (see [feedCow]). `fieldsCompletedTotal`/`pathTypesUnlocked` progress the
     * same for either crop — those track general farming progress, not currency. */
    suspend fun harvest(cellId: Int): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (!cell.occupantType.isCrop()) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase != GrowthPhase.MATURE) return false

        val p = playerDao.get() ?: return false
        val isWheat = cell.occupantType == OccupantType.WHEAT
        updatePlayer(
            p.copy(
                wheatCurrency = if (isWheat) p.wheatCurrency + 5 else p.wheatCurrency,
                carrotInventory = if (isWheat) p.carrotInventory else p.carrotInventory + 1,
                fieldsCompletedTotal = p.fieldsCompletedTotal + 1,
                wheatHarvestedTotal = if (isWheat) p.wheatHarvestedTotal + 1 else p.wheatHarvestedTotal,
                pathTypesUnlocked = (p.pathTypesUnlocked + 1).coerceAtMost(PathType.entries.size)
            )
        )
        cellDao.update(
            cell.copy(
                occupantType = OccupantType.EMPTY,
                plantedAtTimestamp = 0L,
                growthDurationMs = 0L,
                consumedSlotType = null
            )
        )
        return true
    }

    /** Harvests every currently-mature cell in one batch (single player update, one cell write per cell). Returns how many were harvested. */
    suspend fun harvestAll(): Int {
        val now = System.currentTimeMillis()
        val matureCells = cellDao.getAll().filter {
            it.occupantType.isCrop() &&
                GrowthCalculator.computePhase(it.plantedAtTimestamp, it.growthDurationMs, now) == GrowthPhase.MATURE
        }
        if (matureCells.isEmpty()) return 0

        val p = playerDao.get() ?: return 0
        val wheatHarvestedCount = matureCells.count { it.occupantType == OccupantType.WHEAT }
        val carrotHarvestedCount = matureCells.size - wheatHarvestedCount
        updatePlayer(
            p.copy(
                wheatCurrency = p.wheatCurrency + 5 * wheatHarvestedCount,
                carrotInventory = p.carrotInventory + carrotHarvestedCount,
                fieldsCompletedTotal = p.fieldsCompletedTotal + matureCells.size,
                wheatHarvestedTotal = p.wheatHarvestedTotal + wheatHarvestedCount,
                pathTypesUnlocked = (p.pathTypesUnlocked + matureCells.size).coerceAtMost(PathType.entries.size)
            )
        )
        matureCells.forEach { cell ->
            cellDao.update(
                cell.copy(
                    occupantType = OccupantType.EMPTY,
                    plantedAtTimestamp = 0L,
                    growthDurationMs = 0L,
                    consumedSlotType = null
                )
            )
        }
        return matureCells.size
    }

    /** FR-030/031/032 (Should): place a path segment on an empty, in-radius cell. Free, no confirmation. */
    suspend fun buildPath(cellId: Int, pathType: PathType, rotationDegrees: Int = 0): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (cell.occupantType != OccupantType.EMPTY) return false
        val p = playerDao.get() ?: return false
        val config = configFor(p)
        if (!GridMath.isWithinBuildableRadius(cell.col, cell.row, config.cols, config.rows, config.buildableRadius, config.buildingAnchorCol, config.buildingAnchorRow)) return false
        cellDao.update(
            cell.copy(
                occupantType = OccupantType.PATH,
                pathType = pathType,
                pathRotationDegrees = rotationDegrees
            )
        )
        return true
    }

    /** BR-007: destructible objects removed at zero cost, unlimited times. */
    suspend fun removePath(cellId: Int): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (cell.occupantType != OccupantType.PATH) return false
        cellDao.update(cell.copy(occupantType = OccupantType.EMPTY, pathType = null, pathRotationDegrees = 0))
        return true
    }

    /**
     * Founder request "ampliar celdas y alargar el mapa": grows the grid symmetrically
     * by one ring (+2 cols, +2 rows, buildable radius +1), costing wheat currency.
     * Every existing cell (including the 2x2 building) shifts col/row by +1 to make
     * room for the new left/top ring, so its id (which depends on cols) is recomputed;
     * new border cells are inserted as EMPTY. Because the shift is symmetric, the
     * building's position relative to the new grid center falls out unchanged from
     * GridMath.isBuildingCell without any special-casing.
     */
    suspend fun expandGrid(): Boolean {
        val p = playerDao.get() ?: return false
        val cost = 100 * (p.gridExpansionLevel + 1)
        if (p.wheatCurrency < cost) return false

        val oldCols = p.gridCols
        val oldRows = p.gridRows
        val newCols = oldCols + 2
        val newRows = oldRows + 2

        val existingCells = cellDao.getAll()
        val shiftedCells = existingCells.map { cell ->
            val newCol = cell.col + 1
            val newRow = cell.row + 1
            cell.copy(col = newCol, row = newRow, id = GridMath.cellId(newCol, newRow, newCols))
        }

        val newBorderCells = mutableListOf<CellEntity>()
        for (row in 0 until newRows) {
            for (col in 0 until newCols) {
                val isNewBorder = col == 0 || row == 0 || col == newCols - 1 || row == newRows - 1
                if (isNewBorder) {
                    newBorderCells.add(
                        CellEntity(
                            id = GridMath.cellId(col, row, newCols),
                            col = col,
                            row = row,
                            occupantType = OccupantType.EMPTY
                        )
                    )
                }
            }
        }

        cellDao.replaceAll(shiftedCells + newBorderCells)

        updatePlayer(
            p.copy(
                wheatCurrency = p.wheatCurrency - cost,
                gridCols = newCols,
                gridRows = newRows,
                gridExpansionLevel = p.gridExpansionLevel + 1,
                buildableRadius = p.buildableRadius + 1,
                // A null anchor (never manually moved) stays null: the default-center
                // formula already recenters correctly on the new cols/rows because the
                // grid grows symmetrically. A custom anchor (player used "move barn")
                // must shift by the same +1/+1 as every cell above, or the building
                // would visually jump relative to the rest of the map.
                buildingAnchorCol = p.buildingAnchorCol?.plus(1),
                buildingAnchorRow = p.buildingAnchorRow?.plus(1)
            )
        )
        return true
    }

    /** "Move barn" is only offered while no crop (wheat or carrot) is growing/mature
     * anywhere on the map — moving it mid-harvest would silently orphan a crop under
     * the new footprint (its 4 cells are hard-required EMPTY, see [moveBuilding]). */
    suspend fun canRepositionBuilding(): Boolean =
        cellDao.getAll().none { it.occupantType.isCrop() }

    /** True if a building anchored at (anchorCol, anchorRow) would (a) fit inside the
     * buildable ring without touching the fenced-off border and (b) land only on
     * cells that are empty or already part of the *current* building (so tapping the
     * barn's own footprint is always a harmless no-op, not a rejected move). */
    suspend fun isValidBuildingTarget(anchorCol: Int, anchorRow: Int): Boolean {
        val p = playerDao.get() ?: return false
        if (!GridMath.isValidBuildingAnchor(anchorCol, anchorRow, p.gridCols, p.gridRows)) return false
        val oldAnchorCol = p.buildingAnchorCol ?: GridMath.defaultBuildingAnchorCol(p.gridCols)
        val oldAnchorRow = p.buildingAnchorRow ?: GridMath.defaultBuildingAnchorRow(p.gridRows)
        val targetCells = cellDao.getAll().filter { GridMath.isBuildingCell(it.col, it.row, anchorCol, anchorRow) }
        if (targetCells.size != 4) return false
        return targetCells.all {
            it.occupantType == OccupantType.EMPTY || GridMath.isBuildingCell(it.col, it.row, oldAnchorCol, oldAnchorRow)
        }
    }

    /**
     * Founder request "reposicionar el granero": relocates the Farm Building's 2x2
     * footprint to a new anchor, only while [canRepositionBuilding] holds (no wheat
     * anywhere — see its doc). The old footprint's cells revert to EMPTY, the new
     * footprint's cells become BUILDING, and the player's persisted anchor moves —
     * everything else (paths, the fence ring, buildable radius) is untouched.
     */
    suspend fun moveBuilding(newAnchorCol: Int, newAnchorRow: Int): Boolean {
        val p = playerDao.get() ?: return false
        val allCells = cellDao.getAll()
        if (allCells.any { it.occupantType.isCrop() }) return false
        if (!GridMath.isValidBuildingAnchor(newAnchorCol, newAnchorRow, p.gridCols, p.gridRows)) return false

        val oldAnchorCol = p.buildingAnchorCol ?: GridMath.defaultBuildingAnchorCol(p.gridCols)
        val oldAnchorRow = p.buildingAnchorRow ?: GridMath.defaultBuildingAnchorRow(p.gridRows)
        if (newAnchorCol == oldAnchorCol && newAnchorRow == oldAnchorRow) return false

        val targetCells = allCells.filter { GridMath.isBuildingCell(it.col, it.row, newAnchorCol, newAnchorRow) }
        if (targetCells.size != 4) return false
        val targetsAreFree = targetCells.all {
            it.occupantType == OccupantType.EMPTY || GridMath.isBuildingCell(it.col, it.row, oldAnchorCol, oldAnchorRow)
        }
        if (!targetsAreFree) return false

        val changedCells = allCells.mapNotNull { cell ->
            val wasBuilding = GridMath.isBuildingCell(cell.col, cell.row, oldAnchorCol, oldAnchorRow)
            val willBeBuilding = GridMath.isBuildingCell(cell.col, cell.row, newAnchorCol, newAnchorRow)
            when {
                willBeBuilding && !wasBuilding -> cell.copy(occupantType = OccupantType.BUILDING)
                wasBuilding && !willBeBuilding -> cell.copy(occupantType = OccupantType.EMPTY, pathType = null, pathRotationDegrees = 0)
                else -> null
            }
        }
        if (changedCells.isNotEmpty()) cellDao.updateAll(changedCells)

        updatePlayer(p.copy(buildingAnchorCol = newAnchorCol, buildingAnchorRow = newAnchorRow))
        return true
    }

    /**
     * "Solve a problem to save 1 minute" (growing-crop popup): moves the plant
     * timestamp earlier by reductionMs. computePhase/computeProgress already
     * coerce the ratio to MATURE once elapsed >= growthDurationMs, so no extra
     * clamping is needed beyond the same growing-cell guard cancelGrowth uses.
     * Grants no extra-field-today reward — that's a separate flow.
     */
    suspend fun reduceGrowthTime(cellId: Int, reductionMs: Long = 60_000L): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (!cell.occupantType.isCrop()) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase == GrowthPhase.MATURE || phase == GrowthPhase.NONE) return false

        cellDao.update(cell.copy(plantedAtTimestamp = cell.plantedAtTimestamp - reductionMs))
        return true
    }

    /** Casual single-exercise-for-a-field flow (the yellow calculator FAB):
     * +1 extra field per correct answer, no bonus packs — those now live
     * entirely in the separate 10-exercise Challenge flow below, per founder
     * request that the two not be entangled into one continuous dialog. */
    suspend fun recordExerciseResult(correct: Boolean) {
        val p = playerDao.get() ?: return
        updatePlayer(
            if (correct) {
                p.copy(
                    extraFieldsEarnedToday = p.extraFieldsEarnedToday + 1,
                    exercisesSolvedToday = p.exercisesSolvedToday + 1,
                    currentStreak = p.currentStreak + 1
                )
            } else {
                p.copy(currentStreak = 0)
            }
        )
    }

    /**
     * Founder request: a dedicated "solve 10 in a row" challenge, entered via
     * its own button (not folded into the casual exercise flow above). Records
     * one answer of an in-progress challenge attempt (tracked client-side in
     * FarmViewModel, not persisted — a challenge is a one-sitting activity, same
     * as the rest of the exercise UI state). Unlike [recordExerciseResult], a
     * correct answer here does **not** grant the usual +1 field — a challenge's
     * only reward is the lump [grantChallengeBonus] pack on full completion.
     * Still updates the shared `exercisesSolvedToday`/`currentStreak` stats so
     * the Stats dialog and any future streak UI stay consistent regardless of
     * which flow the player used.
     */
    suspend fun recordChallengeAnswer(correct: Boolean) {
        val p = playerDao.get() ?: return
        updatePlayer(
            if (correct) {
                p.copy(exercisesSolvedToday = p.exercisesSolvedToday + 1, currentStreak = p.currentStreak + 1)
            } else {
                p.copy(currentStreak = 0)
            }
        )
    }

    /** Grants a completed challenge attempt's reward: a random
     * [CHALLENGE_BONUS_MIN]-[CHALLENGE_BONUS_MAX] field bonus **pack** in one
     * go, landing in the same `extraFieldsEarnedToday` pool the casual
     * +1-per-answer reward uses — fields can unlock in bulk this way, not only
     * individually. Called once by FarmViewModel when a challenge attempt
     * reaches its full length. */
    suspend fun grantChallengeBonus(): Int {
        val p = playerDao.get() ?: return 0
        val bonus = Random.nextInt(CHALLENGE_BONUS_MIN, CHALLENGE_BONUS_MAX + 1)
        updatePlayer(p.copy(extraFieldsEarnedToday = p.extraFieldsEarnedToday + bonus))
        return bonus
    }

    suspend fun setAgeBand(ageBand: AgeBand) {
        val p = playerDao.get() ?: return
        updatePlayer(p.copy(ageBand = ageBand))
    }

    suspend fun updateSettings(mutator: (SettingsEntity) -> SettingsEntity) {
        val s = settingsDao.get() ?: SettingsEntity()
        settingsDao.update(mutator(s))
    }

    /** Feeding the wandering cow costs 1 harvested carrot (her entire purpose,
     * per founder request) — consumes it from [PlayerEntity.carrotInventory] and
     * resets her fed timestamp. Only meaningful while she's hungry (enforced by
     * the tap hit-test in FarmGridCanvas, not re-checked here since there's no
     * penalty for a redundant feed), but always requires the carrot regardless.
     * Returns false (no state change) when the player has none to feed her. */
    suspend fun feedCow(): Boolean {
        val p = playerDao.get() ?: return false
        if (p.carrotInventory <= 0) return false
        updatePlayer(
            p.copy(
                cowLastFedTimestamp = System.currentTimeMillis(),
                carrotInventory = p.carrotInventory - 1
            )
        )
        return true
    }

    suspend fun currentPlayer(): PlayerEntity? = player.first()

    companion object {
        const val TEST_MIN_WHEAT = 100
        /** Founder request: carrot unlocks after this many wheat harvests specifically. */
        const val CARROT_UNLOCK_WHEAT_HARVESTS = 50
        /** Founder request: the dedicated Challenge is this many consecutive correct answers. */
        const val EXERCISE_STREAK_CHALLENGE_LENGTH = 10
        const val CHALLENGE_BONUS_MIN = 5
        const val CHALLENGE_BONUS_MAX = 10
    }
}
