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
import com.farmmathbuilder.app.domain.PathType
import com.farmmathbuilder.app.domain.SlotAvailability
import com.farmmathbuilder.app.domain.TextSizeOption
import com.farmmathbuilder.app.domain.UiCell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    val uiCells: Flow<List<UiCell>> = cells.combine(player) { cellList, p ->
        val config = p?.let { GridConfig(it.gridCols, it.gridRows, it.buildableRadius) } ?: GridConfig.BASE
        cellList.map { it.toUiCell(config = config) }
    }

    private fun CellEntity.toUiCell(now: Long = System.currentTimeMillis(), config: GridConfig): UiCell {
        val phase = if (occupantType == OccupantType.WHEAT) {
            GrowthCalculator.computePhase(plantedAtTimestamp, growthDurationMs, now)
        } else GrowthPhase.NONE
        val progress = if (occupantType == OccupantType.WHEAT) {
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
            isBuildingCell = GridMath.isBuildingCell(col, row, config.cols, config.rows),
            isWithinBuildableRadius = GridMath.isWithinBuildableRadius(col, row, config.cols, config.rows, config.buildableRadius)
        )
    }

    /** Creates the starting BASE_COLS x BASE_ROWS grid with the central 2x2 Farm Building. */
    suspend fun ensureGridInitialized() {
        if (cellDao.count() > 0) return
        val cols = GridMath.BASE_COLS
        val rows = GridMath.BASE_ROWS
        val cells = mutableListOf<CellEntity>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val occupant = if (GridMath.isBuildingCell(col, row, cols, rows)) OccupantType.BUILDING else OccupantType.EMPTY
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
        if (playerDao.get() == null) {
            playerDao.insert(PlayerEntity(lastDailyResetTimestamp = System.currentTimeMillis()))
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

        playerDao.update(
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

    /** Plants wheat into an empty cell, consuming a free or extra slot per the decision table. */
    suspend fun plantWheat(cellId: Int): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (cell.occupantType != OccupantType.EMPTY) return false
        val p = playerDao.get() ?: return false
        val availability = slotAvailability(p)
        if (availability == SlotAvailability.NONE_AVAILABLE) return false

        val slotType = if (availability == SlotAvailability.FREE_SLOT_AVAILABLE) "FREE" else "EXTRA"

        cellDao.update(
            cell.copy(
                occupantType = OccupantType.WHEAT,
                plantedAtTimestamp = System.currentTimeMillis(),
                growthDurationMs = GrowthCalculator.NORMAL_GROWTH_DURATION_MS,
                consumedSlotType = slotType
            )
        )

        playerDao.update(
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
        if (cell.occupantType != OccupantType.WHEAT) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase == GrowthPhase.MATURE || phase == GrowthPhase.NONE) return false

        val p = playerDao.get() ?: return false
        val restoredPlayer = when (cell.consumedSlotType) {
            "FREE" -> p.copy(freeFieldsUsedToday = (p.freeFieldsUsedToday - 1).coerceAtLeast(0))
            "EXTRA" -> p.copy(extraFieldsUsedToday = (p.extraFieldsUsedToday - 1).coerceAtLeast(0))
            else -> p
        }
        playerDao.update(restoredPlayer)

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

    /** FR-015: harvest a mature crop for +5 wheat currency (BR-003), cell returns to empty. */
    suspend fun harvest(cellId: Int): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (cell.occupantType != OccupantType.WHEAT) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase != GrowthPhase.MATURE) return false

        val p = playerDao.get() ?: return false
        playerDao.update(
            p.copy(
                wheatCurrency = p.wheatCurrency + 5,
                fieldsCompletedTotal = p.fieldsCompletedTotal + 1,
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

    /** FR-030/031/032 (Should): place a path segment on an empty, in-radius cell. Free, no confirmation. */
    suspend fun buildPath(cellId: Int, pathType: PathType, rotationDegrees: Int = 0): Boolean {
        val cell = cellDao.getById(cellId) ?: return false
        if (cell.occupantType != OccupantType.EMPTY) return false
        val p = playerDao.get() ?: return false
        if (!GridMath.isWithinBuildableRadius(cell.col, cell.row, p.gridCols, p.gridRows, p.buildableRadius)) return false
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

        playerDao.update(
            p.copy(
                wheatCurrency = p.wheatCurrency - cost,
                gridCols = newCols,
                gridRows = newRows,
                gridExpansionLevel = p.gridExpansionLevel + 1,
                buildableRadius = p.buildableRadius + 1
            )
        )
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
        if (cell.occupantType != OccupantType.WHEAT) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase == GrowthPhase.MATURE || phase == GrowthPhase.NONE) return false

        cellDao.update(cell.copy(plantedAtTimestamp = cell.plantedAtTimestamp - reductionMs))
        return true
    }

    suspend fun recordExerciseResult(correct: Boolean) {
        val p = playerDao.get() ?: return
        playerDao.update(
            if (correct) {
                p.copy(
                    extraFieldsEarnedToday = p.extraFieldsEarnedToday + 1,
                    exercisesSolvedToday = p.exercisesSolvedToday + 1,
                    currentStreak = p.currentStreak + 1
                )
            } else {
                p.copy(
                    exercisesSolvedToday = p.exercisesSolvedToday,
                    currentStreak = 0
                )
            }
        )
    }

    suspend fun setAgeBand(ageBand: AgeBand) {
        val p = playerDao.get() ?: return
        playerDao.update(p.copy(ageBand = ageBand))
    }

    suspend fun updateSettings(mutator: (SettingsEntity) -> SettingsEntity) {
        val s = settingsDao.get() ?: SettingsEntity()
        settingsDao.update(mutator(s))
    }

    suspend fun currentPlayer(): PlayerEntity? = player.first()
}
