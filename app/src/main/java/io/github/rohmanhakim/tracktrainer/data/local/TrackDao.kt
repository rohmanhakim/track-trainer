package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<Track>>

    @Insert
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): Track?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** Tracks whose cache keys must never be evicted (Phase 7 pin backing semantics). */
    @Query("SELECT * FROM tracks WHERE pinned = 1")
    fun observePinnedTracks(): Flow<List<Track>>
}