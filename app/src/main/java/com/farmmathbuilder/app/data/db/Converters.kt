package com.farmmathbuilder.app.data.db

import androidx.room.TypeConverter
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.domain.AnimalType
import com.farmmathbuilder.app.domain.DecorationSide
import com.farmmathbuilder.app.domain.DecorationType
import com.farmmathbuilder.app.domain.OccupantType
import com.farmmathbuilder.app.domain.PathType
import com.farmmathbuilder.app.domain.TextSizeOption

class Converters {
    @TypeConverter
    fun fromAnimalType(value: AnimalType): String = value.name

    @TypeConverter
    fun toAnimalType(value: String): AnimalType = AnimalType.valueOf(value)
    @TypeConverter
    fun fromOccupantType(value: OccupantType): String = value.name

    @TypeConverter
    fun toOccupantType(value: String): OccupantType = OccupantType.valueOf(value)

    @TypeConverter
    fun fromPathType(value: PathType?): String? = value?.name

    @TypeConverter
    fun toPathType(value: String?): PathType? = value?.let { PathType.valueOf(it) }

    @TypeConverter
    fun fromAgeBand(value: AgeBand?): String? = value?.name

    @TypeConverter
    fun toAgeBand(value: String?): AgeBand? = value?.let { AgeBand.valueOf(it) }

    @TypeConverter
    fun fromTextSize(value: TextSizeOption): String = value.name

    @TypeConverter
    fun toTextSize(value: String): TextSizeOption = TextSizeOption.valueOf(value)

    @TypeConverter
    fun fromDecorationType(value: DecorationType): String = value.name

    @TypeConverter
    fun toDecorationType(value: String): DecorationType = DecorationType.valueOf(value)

    @TypeConverter
    fun fromDecorationSide(value: DecorationSide): String = value.name

    @TypeConverter
    fun toDecorationSide(value: String): DecorationSide = DecorationSide.valueOf(value)
}
