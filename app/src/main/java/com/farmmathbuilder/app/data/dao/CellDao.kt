package com.farmmathbuilder.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.farmmathbuilder.app.data.entity.CellEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CellDao {
    @Query("SELECT * FROM cells ORDER BY id ASC")
    fun observeAll(): Flow<List<CellEntity>>

    @Query("SELECT * FROM cells ORDER BY id ASC")
    suspend fun getAll(): List<CellEntity>

    @Query("SELECT * FROM cells WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): CellEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cells: List<CellEntity>)

    @Update
    suspend fun update(cell: CellEntity)

    @Update
    suspend fun updateAll(cells: List<CellEntity>)

    @Query("SELECT COUNT(*) FROM cells")
    suspend fun count(): Int

    @Query("DELETE FROM cells")
    suspend fun deleteAll()

    /**
     * Used by map expansion (FarmRepository.expandGrid): every existing cell's
     * col/row/id changes when the grid grows, so we replace the whole table in one
     * transaction rather than trying to @Update rows whose primary key is moving.
     */
    @Transaction
    suspend fun replaceAll(cells: List<CellEntity>) {
        deleteAll()
        insertAll(cells)
    }
}
