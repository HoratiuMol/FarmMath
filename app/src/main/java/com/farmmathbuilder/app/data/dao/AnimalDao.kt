package com.farmmathbuilder.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.farmmathbuilder.app.data.entity.AnimalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimalDao {
    @Query("SELECT * FROM animals ORDER BY id ASC")
    fun observeAll(): Flow<List<AnimalEntity>>

    @Query("SELECT * FROM animals ORDER BY id ASC")
    suspend fun getAll(): List<AnimalEntity>

    @Query("SELECT * FROM animals WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): AnimalEntity?

    @Query("SELECT COUNT(*) FROM animals")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(animal: AnimalEntity): Long

    @Update
    suspend fun update(animal: AnimalEntity)

    @Query("DELETE FROM animals WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM animals")
    suspend fun deleteAll()
}
