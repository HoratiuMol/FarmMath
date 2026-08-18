package com.farmmathbuilder.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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

@Database(
    entities = [CellEntity::class, PlayerEntity::class, SettingsEntity::class, AnimalEntity::class, DecorationEntity::class],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cellDao(): CellDao
    abstract fun playerDao(): PlayerDao
    abstract fun settingsDao(): SettingsDao
    abstract fun animalDao(): AnimalDao
    abstract fun decorationDao(): DecorationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "farm_math_builder.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
