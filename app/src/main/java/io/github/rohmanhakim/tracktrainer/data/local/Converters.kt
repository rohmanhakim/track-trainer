package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType = SourceType.valueOf(value)

    @TypeConverter
    fun clickSoundToString(value: ClickSound): String = value.name

    @TypeConverter
    fun stringToClickSound(value: String): ClickSound = ClickSound.valueOf(value)
}