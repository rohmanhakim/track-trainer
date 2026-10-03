package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<Track>>

    @Insert
    suspend fun insert(track: Track): Long

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int
}