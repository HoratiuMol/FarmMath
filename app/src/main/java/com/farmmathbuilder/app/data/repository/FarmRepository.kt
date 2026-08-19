package com.farmmathbuilder.app.data.repository

import com.farmmathbuilder.app.data.dao.AnimalDao
import com.farmmathbuilder.app.data.dao.CellDao
import com.farmmathbuilder.app.data.dao.DecorationDao
import com.farmmathbuilder.app.data.dao.PlayerDao
import com.farmmathbuilder.app.data.dao.SettingsDao
import com.farmmathbuilder.app.data.entity.AnimalEntity
import com.farmmathbuilder.app.data.entity.CellEntity
import com.farmmathbuilder.app.data.entity.DecorationEntity
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.domain.AnimalGrowth
import com.farmmathbuilder.app.domain.AnimalLifespan
import com.farmmathbuilder.app.domain.AnimalType
import com.farmmathbuilder.app.domain.DecorationSide
import com.farmmathbuilder.app.domain.DecorationType
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
    private val settingsDao: SettingsDao,
    private val animalDao: AnimalDao,
    private val decorationDao: DecorationDao
) {

    val cells: Flow<List<CellEntity>> = cellDao.observeAll()
    val player: Flow<PlayerEntity?> = playerDao.observe()
    val settings: Flow<SettingsEntity?> = settingsDao.observe()
    val animals: Flow<List<AnimalEntity>> = animalDao.observeAll()
    val decorations: Flow<List<DecorationEntity>> = decorationDao.observeAll()

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

    /** Resolves a player's persisted grid state into a [GridConfig]. */
    private fun configFor(p: PlayerEntity?): GridConfig {
        if (p == null) return GridConfig.BASE
        return GridConfig(cols = p.gridCols, rows = p.gridRows)
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
            isBuildable = GridMath.isBuildable(col, row, config.cols, config.rows)
        )
    }

    /** Creates the starting BASE_COLS x BASE_ROWS grid. The Farm Building lives
     * entirely outside the grid now (see FarmGridCanvas), so every cell starts
     * EMPTY — no footprint to carve out. */
    suspend fun ensureGridInitialized() {
        if (cellDao.count() > 0) return
        val cols = GridMath.BASE_COLS
        val rows = GridMath.BASE_ROWS
        val cells = mutableListOf<CellEntity>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                cells.add(
                    CellEntity(
                        id = GridMath.cellId(col, row, cols),
                        col = col,
                        row = row,
                        occupantType = OccupantType.EMPTY
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
                    // Founder request 2026-08-18: a fresh save starts with 10
                    // carrots already stockpiled (feeds the 2 starting cows a
                    // few times before the player needs to grow/harvest any).
                    carrotInventory = STARTING_CARROTS
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

    /** Seeds the player's starting herd — founder request 2026-08-18: 2 cows
     * (was 1) so breeding/feeding has something to work with from minute one. */
    suspend fun ensureAnimalsInitialized() {
        if (animalDao.count() > 0) return
        val now = System.currentTimeMillis()
        repeat(STARTING_COWS) {
            animalDao.insert(
                AnimalEntity(
                    type = AnimalType.COW,
                    // Backdated so she's already an adult, not a newborn calf.
                    bornAtTimestamp = now - AnimalGrowth.CALF_GROWTH_DURATION_MS,
                    // Starts "just fed" so a new save doesn't open with the hunger
                    // icon already showing — see CowHunger.
                    lastFedTimestamp = now,
                    // NOT backdated, unlike bornAtTimestamp above — her 20-minute
                    // lifespan (AnimalLifespan) starts from actual real time.
                    spawnedAtTimestamp = now
                )
            )
        }
    }

    /** Seeds the map's standard starting decoration — founder request
     * 2026-08-18: a river at its original fixed spot (top edge, 38% of the way
     * along it — the same position the old always-on backdrop used before
     * decorations became player-placed). Counts toward [maxDecorations], so the
     * first *additional* river only becomes placeable after the first map
     * expansion. */
    suspend fun ensureDecorationsInitialized() {
        if (decorationDao.count() > 0) return
        decorationDao.insert(DecorationEntity(type = DecorationType.RIVER, side = DecorationSide.TOP, alongFraction = 0.38f))
    }

    /**
     * Settings menu "New world" (founder request): wipes cells/player/animals/
     * decorations and reseeds a fresh starting save, same shape [initializeAll]
     * builds for a brand new install — settings (sound/text/notifications
     * prefs) are deliberately left untouched since those are app preferences,
     * not world state.
     */
    suspend fun resetWorld() {
        cellDao.deleteAll()
        animalDao.deleteAll()
        decorationDao.deleteAll()
        playerDao.insert(
            PlayerEntity(
                lastDailyResetTimestamp = System.currentTimeMillis(),
                wheatCurrency = TEST_MIN_WHEAT,
                carrotInventory = STARTING_CARROTS
            )
        )
        ensureGridInitialized()
        ensureAnimalsInitialized()
        ensureDecorationsInitialized()
    }

    suspend fun initializeAll() {
        ensureGridInitialized()
        ensurePlayerInitialized()
        ensureSettingsInitialized()
        ensureAnimalsInitialized()
        ensureDecorationsInitialized()
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
                // exercisesSolvedToday/dailyMissionClaimed previously weren't
                // reset here despite the "Today" name — a pre-existing gap
                // that made the daily math mission below unable to repeat.
                exercisesSolvedToday = 0,
                dailyMissionClaimed = false,
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
        if (!GridMath.isBuildable(cell.col, cell.row, config.cols, config.rows)) return false
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
     * by one ring (+2 cols, +2 rows), costing wheat currency. Every existing cell
     * shifts col/row by +1 to make room for the new left/top ring, so its id (which
     * depends on cols) is recomputed; new border cells are inserted as EMPTY. The
     * Farm Building lives outside the grid (see FarmGridCanvas) and is repositioned
     * for free every expansion purely from cols/rows at draw time, so there's no
     * player-facing anchor to shift here any more.
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
                gridExpansionLevel = p.gridExpansionLevel + 1
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
        if (!cell.occupantType.isCrop()) return false
        val phase = GrowthCalculator.computePhase(cell.plantedAtTimestamp, cell.growthDurationMs)
        if (phase == GrowthPhase.MATURE || phase == GrowthPhase.NONE) return false

        cellDao.update(cell.copy(plantedAtTimestamp = cell.plantedAtTimestamp - reductionMs))
        return true
    }

    /** Casual single-exercise-for-a-field flow (the yellow calculator FAB):
     * +1 extra field per correct answer, no bonus packs — those now live
     * entirely in the separate 10-exercise Challenge flow below, per founder
     * request that the two not be entangled into one continuous dialog.
     * Returns true if this answer just completed today's daily math mission
     * (see [applyDailyMission]), so the caller can surface a celebration. */
    suspend fun recordExerciseResult(correct: Boolean): Boolean {
        val p = playerDao.get() ?: return false
        if (!correct) {
            updatePlayer(p.copy(currentStreak = 0))
            return false
        }
        val updated = p.copy(
            extraFieldsEarnedToday = p.extraFieldsEarnedToday + 1,
            exercisesSolvedToday = p.exercisesSolvedToday + 1,
            currentStreak = p.currentStreak + 1,
            mathStars = p.mathStars + 1
        )
        val (finalPlayer, missionCompleted) = applyDailyMission(updated)
        updatePlayer(finalPlayer)
        return missionCompleted
    }

    /** Grants the daily math mission's one-time bonus the moment
     * [candidate]'s exercisesSolvedToday first reaches [DAILY_MISSION_TARGET]
     * — shared by both [recordExerciseResult] and [recordChallengeAnswer] so
     * either flow can complete it. Returns the (possibly bonus-adjusted)
     * player alongside whether this call is the one that completed it. */
    private fun applyDailyMission(candidate: PlayerEntity): Pair<PlayerEntity, Boolean> {
        if (candidate.dailyMissionClaimed || candidate.exercisesSolvedToday < DAILY_MISSION_TARGET) {
            return candidate to false
        }
        return candidate.copy(
            dailyMissionClaimed = true,
            mathStars = candidate.mathStars + DAILY_MISSION_STAR_BONUS
        ) to true
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
     * which flow the player used. Returns true if this answer just completed
     * today's daily math mission (see [applyDailyMission]).
     */
    suspend fun recordChallengeAnswer(correct: Boolean): Boolean {
        val p = playerDao.get() ?: return false
        if (!correct) {
            updatePlayer(p.copy(currentStreak = 0))
            return false
        }
        val updated = p.copy(
            exercisesSolvedToday = p.exercisesSolvedToday + 1,
            currentStreak = p.currentStreak + 1,
            mathStars = p.mathStars + 1
        )
        val (finalPlayer, missionCompleted) = applyDailyMission(updated)
        updatePlayer(finalPlayer)
        return missionCompleted
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
        updatePlayer(
            p.copy(
                extraFieldsEarnedToday = p.extraFieldsEarnedToday + bonus,
                mathStars = p.mathStars + CHALLENGE_COMPLETE_STAR_BONUS
            )
        )
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

    // ---------- Animals (cows) ----------

    /** Founder request: population cap grows with the map — 5 base slots, +5 per
     * expansion (gridExpansionLevel is already 0-indexed, incrementing once per
     * expandGrid() call), so a fresh save caps at 5 and each expansion adds 5 more. */
    fun maxCows(p: PlayerEntity): Int = COW_BASE_CAP + COW_CAP_PER_EXPANSION * p.gridExpansionLevel

    /** Buys one adult cow for [COW_COST] wheat, blocked while short on wheat or
     * already at [maxCows]. Bred calves (see [feedAnimal]) count toward the same
     * cap, so this also fails once breeding alone has filled the pen. */
    suspend fun buyCow(): Boolean {
        val p = playerDao.get() ?: return false
        if (p.wheatCurrency < COW_COST) return false
        if (animalDao.count() >= maxCows(p)) return false

        val now = System.currentTimeMillis()
        animalDao.insert(
            AnimalEntity(
                type = AnimalType.COW,
                bornAtTimestamp = now - AnimalGrowth.CALF_GROWTH_DURATION_MS,
                lastFedTimestamp = now,
                spawnedAtTimestamp = now
            )
        )
        updatePlayer(p.copy(wheatCurrency = p.wheatCurrency - COW_COST))
        return true
    }

    /** Feeding any animal costs 1 harvested carrot (her entire purpose, per
     * founder request) and resets its own fed timestamp — replaces the old
     * single-cow [PlayerEntity.cowLastFedTimestamp] now that there's a list.
     * Only meaningful while an animal is hungry (enforced by the tap hit-test
     * in FarmGridCanvas, not re-checked here since there's no penalty for a
     * redundant feed), but always requires the carrot regardless.
     *
     * Breeding (founder request): every 2nd feed action — any animal, tracked
     * by [PlayerEntity.cowFeedsSinceBreedingRoll] — rolls a [COW_BREEDING_CHANCE]
     * chance to spawn a new calf, as long as the herd is under [maxCows].
     */
    suspend fun feedAnimal(animalId: Int): CowFeedResult {
        val p = playerDao.get() ?: return CowFeedResult(fed = false, calfBorn = false)
        if (p.carrotInventory <= 0) return CowFeedResult(fed = false, calfBorn = false)
        val animal = animalDao.getById(animalId) ?: return CowFeedResult(fed = false, calfBorn = false)

        val now = System.currentTimeMillis()
        animalDao.update(animal.copy(lastFedTimestamp = now))

        val feedCount = p.cowFeedsSinceBreedingRoll + 1
        var calfBorn = false
        var nextFeedCount = feedCount
        if (feedCount >= 2) {
            nextFeedCount = 0
            if (animalDao.count() < maxCows(p) && Random.nextFloat() < COW_BREEDING_CHANCE) {
                animalDao.insert(AnimalEntity(type = AnimalType.COW, bornAtTimestamp = now, lastFedTimestamp = now, spawnedAtTimestamp = now))
                calfBorn = true
            }
        }

        updatePlayer(
            p.copy(
                carrotInventory = p.carrotInventory - 1,
                cowFeedsSinceBreedingRoll = nextFeedCount
            )
        )
        return CowFeedResult(fed = true, calfBorn = calfBorn)
    }

    /** Removes every cow past [AnimalLifespan.LIFESPAN_MS] and returns the ones
     * removed (so the ViewModel can surface a "a cow died" moment) — called once
     * per ticker second from FarmViewModel, same cadence as the growth/hunger
     * recompute. No floor on herd size: if every cow dies, the player buys or
     * breeds a new one same as starting from zero. */
    suspend fun removeDeadAnimals(now: Long = System.currentTimeMillis()): List<AnimalEntity> {
        val dead = animalDao.getAll().filter { AnimalLifespan.isDead(it.spawnedAtTimestamp, now) }
        dead.forEach { animalDao.deleteById(it.id) }
        return dead
    }

    suspend fun currentPlayer(): PlayerEntity? = player.first()

    /** [fed]: whether the carrot was spent and the animal's hunger reset (false
     * only when out of carrots or the animal id no longer exists). [calfBorn]:
     * whether this feed happened to be the 2nd-in-a-row breeding roll and it
     * succeeded — lets the ViewModel surface a "a calf was born!" moment. */
    data class CowFeedResult(val fed: Boolean, val calfBorn: Boolean)

    // ---------- Map decorations (founder request: "accidentes geográficos" shop) ----------

    /** Founder request 2026-08-18 ("por cada expansión es un elemento decorativo
     * geográfico nuevo"): population cap grows with the map — 1 base slot
     * (the standard river every save starts with, see [ensureDecorationsInitialized])
     * +1 per expansion — same shape as [maxCows]. */
    fun maxDecorations(p: PlayerEntity): Int = DECORATION_BASE_CAP + p.gridExpansionLevel

    /**
     * Places a *new* decoration of [type] at a spot on border edge [side],
     * [alongFraction] of the way along it (0..1) — founder request 2026-08-18:
     * picking a decoration always adds another instance rather than moving an
     * existing one ("si selecciono el río, me pone un río nuevo, no me mueve el
     * actual"), gated by [maxDecorations] so the count of decorations tracks
     * map expansions the same way the cow pen does. Free (no wheat cost) —
     * this is pure cosmetics, not economy. The stored (side, alongFraction) is
     * deliberately relative rather than an absolute col/row — FarmGridCanvas
     * re-projects it onto the grid's *current* cols/rows every frame, so it
     * automatically stays outside the fence and in the same relative spot
     * through every future map expansion with no migration needed here.
     */
    suspend fun placeDecoration(type: DecorationType, side: DecorationSide, alongFraction: Float): Boolean {
        val p = playerDao.get() ?: return false
        if (decorationDao.count() >= maxDecorations(p)) return false
        decorationDao.insert(DecorationEntity(type = type, side = side, alongFraction = alongFraction.coerceIn(0f, 1f)))
        return true
    }

    companion object {
        const val TEST_MIN_WHEAT = 100
        /** Founder request: carrot unlocks after this many wheat harvests specifically. */
        const val CARROT_UNLOCK_WHEAT_HARVESTS = 50
        /** Founder request: the dedicated Challenge is this many consecutive correct answers. */
        const val EXERCISE_STREAK_CHALLENGE_LENGTH = 10
        /** Daily math mission (gameplay push: "misión del día"): solve this many
         * correct answers today, from either flow, to earn [DAILY_MISSION_STAR_BONUS]. */
        const val DAILY_MISSION_TARGET = 3
        const val DAILY_MISSION_STAR_BONUS = 3
        const val CHALLENGE_BONUS_MIN = 5
        const val CHALLENGE_BONUS_MAX = 10
        /** Extra mathStars on top of the usual +1-per-answer, awarded once when
         * a full 10-in-a-row Challenge attempt completes. */
        const val CHALLENGE_COMPLETE_STAR_BONUS = 5
        /** Founder request: a cow costs 20 wheat. */
        const val COW_COST = 20
        const val COW_BASE_CAP = 5
        const val COW_CAP_PER_EXPANSION = 5
        /** Founder request: 25% chance per breeding roll (every 2nd feed). */
        const val COW_BREEDING_CHANCE = 0.25f
        /** Founder request 2026-08-18: a fresh save/new world starts with 2 cows
         * (was 1) and 10 carrots already stockpiled to feed them. */
        const val STARTING_COWS = 2
        const val STARTING_CARROTS = 10
        /** Founder request 2026-08-18: 1 decoration slot to start (the standard
         * river), +1 per map expansion — see [maxDecorations]. */
        const val DECORATION_BASE_CAP = 1
    }
}
