package io.github.rohmanhakim.tracktrainer.data.repo

import io.github.rohmanhakim.tracktrainer.data.local.Preset
import io.github.rohmanhakim.tracktrainer.data.local.PresetDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Thin DAO wrapper for presets. Mirrors TrackRepository.
 *
 * There is at most one preset row per track; callers enforce this via [upsert].
 */
@Singleton
class PresetRepository @Inject constructor(
    private val presetDao: PresetDao,
) {
    fun observePreset(trackId: Long): Flow<List<Preset>> = presetDao.observeByTrack(trackId)

    suspend fun getByTrack(trackId: Long): Preset? = presetDao.getByTrack(trackId)

    /** Inserts a new preset, or replaces the existing one for the track. */
    suspend fun upsert(preset: Preset): Long {
        val existing = presetDao.getByTrack(preset.trackId)
        return if (existing != null) {
            presetDao.update(preset.copy(id = existing.id))
            existing.id
        } else {
            presetDao.insert(preset)
        }
    }

    suspend fun update(preset: Preset) = presetDao.update(preset)

    suspend fun remove(preset: Preset) = presetDao.delete(preset)
}
