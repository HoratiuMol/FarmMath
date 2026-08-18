package com.farmmathbuilder.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.farmmathbuilder.app.data.entity.DecorationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DecorationDao {
    @Query("SELECT * FROM decorations")
    fun observeAll(): Flow<List<DecorationEntity>>

    @Query("SELECT * FROM decorations")
    suspend fun getAll(): List<DecorationEntity>

    @Query("SELECT COUNT(*) FROM decorations")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(decoration: DecorationEntity): Long

    @Query("DELETE FROM decorations")
    suspend fun deleteAll()
}
