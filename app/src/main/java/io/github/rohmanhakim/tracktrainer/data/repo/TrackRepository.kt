package io.github.rohmanhakim.tracktrainer.data.repo

import io.github.rohmanhakim.tracktrainer.data.local.Track
import io.github.rohmanhakim.tracktrainer.data.local.TrackDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Thin DAO-backed repository (T03 scope); richer logic lands with later stories. */
@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
) {
    fun observeTracks(): Flow<List<Track>> = trackDao.observeAll()

    suspend fun add(track: Track): Long = trackDao.insert(track)
}
