package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LoopPointDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: LoopPointDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.application, AppDatabase::class.java).build()
        dao = database.loopPointDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetById() = runBlocking {
        val id = dao.insert(LoopPoint(trackId = 1, label = "Chorus", positionA = 1000, positionB = 5000))
        assertTrue("id should be positive", id > 0)
        val point = dao.getById(id)
        assertNotNull(point)
        assertEquals("Chorus", point!!.label)
        assertEquals(1000L, point!!.positionA)
        assertEquals(5000L, point!!.positionB)
    }

    @Test
    fun observeByTrackReturnsOnlyThatTracksPoints() = runBlocking {
        dao.insert(LoopPoint(trackId = 1, label = "A", positionA = 0, positionB = 1000))
        dao.insert(LoopPoint(trackId = 2, label = "B", positionA = 0, positionB = 1000))
        val list = dao.observeByTrack(1).first()
        assertEquals(1, list.size)
        assertEquals("A", list[0].label)
    }

    @Test
    fun updateAndDelete() = runBlocking {
        val id = dao.insert(LoopPoint(trackId = 1, label = "A", positionA = 0, positionB = 1000))
        dao.update(LoopPoint(id = id, trackId = 1, label = "B", positionA = 0, positionB = 1000))
        assertEquals("B", dao.getById(id)!!.label)
        dao.delete(LoopPoint(id = id, trackId = 1, label = "B", positionA = 0, positionB = 1000))
        assertEquals(0, dao.observeByTrack(1).first().size)
    }
}
