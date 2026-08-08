package com.farmmathbuilder.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.farmmathbuilder.app.domain.TextSizeOption

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val musicVolume: Float = 0.7f,
    val sfxVolume: Float = 0.7f,
    val muted: Boolean = false,
    val textSize: TextSizeOption = TextSizeOption.MEDIUM,
    val highContrast: Boolean = false,
    val notificationsEnabled: Boolean = true
)
