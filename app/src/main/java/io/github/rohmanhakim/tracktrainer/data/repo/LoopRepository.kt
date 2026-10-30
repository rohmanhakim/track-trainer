package io.github.rohmanhakim.tracktrainer.data.repo

import io.github.rohmanhakim.tracktrainer.data.local.LoopPoint
import io.github.rohmanhakim.tracktrainer.data.local.LoopPointDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Thin DAO wrapper for loop points. Mirrors TrackRepository. */
@Singleton
class LoopRepository @Inject constructor(
    private val loopPointDao: LoopPointDao,
) {
    fun observeLoopPoints(trackId: Long): Flow<List<LoopPoint>> =
        loopPointDao.observeByTrack(trackId)

    suspend fun getById(id: Long): LoopPoint? = loopPointDao.getById(id)

    suspend fun add(loopPoint: LoopPoint): Long = loopPointDao.insert(loopPoint)

    suspend fun update(loopPoint: LoopPoint) = loopPointDao.update(loopPoint)

    suspend fun remove(loopPoint: LoopPoint) = loopPointDao.delete(loopPoint)
}
