package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LoopPointDao {
    @Query("SELECT * FROM loop_points WHERE trackId = :trackId ORDER BY id")
    fun observeByTrack(trackId: Long): Flow<List<LoopPoint>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(loopPoint: LoopPoint): Long

    @Update
    suspend fun update(loopPoint: LoopPoint)

    @Delete
    suspend fun delete(loopPoint: LoopPoint)

    @Query("SELECT * FROM loop_points WHERE id = :id")
    suspend fun getById(id: Long): LoopPoint?
}
