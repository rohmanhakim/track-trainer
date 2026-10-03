package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Track::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "track-trainer.db"
    }
}