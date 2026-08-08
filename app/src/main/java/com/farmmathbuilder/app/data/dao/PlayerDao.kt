package com.farmmathbuilder.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.farmmathbuilder.app.data.entity.PlayerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    @Query("SELECT * FROM player WHERE id = 1 LIMIT 1")
    fun observe(): Flow<PlayerEntity?>

    @Query("SELECT * FROM player WHERE id = 1 LIMIT 1")
    suspend fun get(): PlayerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(player: PlayerEntity)

    @Update
    suspend fun update(player: PlayerEntity)
}
