package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets WHERE trackId = :trackId ORDER BY id LIMIT 1")
    fun observeByTrack(trackId: Long): Flow<List<Preset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: Preset): Long

    @Update
    suspend fun update(preset: Preset)

    @Delete
    suspend fun delete(preset: Preset)

    @Query("SELECT * FROM presets WHERE trackId = :trackId")
    suspend fun getByTrack(trackId: Long): Preset?
}
