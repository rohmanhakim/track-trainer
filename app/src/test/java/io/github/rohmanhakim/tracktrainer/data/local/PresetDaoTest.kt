package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class PresetDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PresetDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.application, AppDatabase::class.java).build()
        dao = database.presetDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetByTrack() = runBlocking {
        val id = dao.insert(Preset(trackId = 1, speed = 1.5f))
        assertTrue("id should be positive", id > 0)
        val preset = dao.getByTrack(1)
        assertNotNull(preset)
        assertEquals(1.5f, preset!!.speed, 0.001f)
    }

    @Test
    fun getByTrackReturnsNullWhenAbsent() = runBlocking {
        assertNull(dao.getByTrack(1))
    }

    @Test
    fun defaultsArePersisted() = runBlocking {
        dao.insert(Preset(trackId = 1))
        val preset = dao.getByTrack(1) ?: error("expected preset")
        assertEquals(1f, preset.speed, 0.001f)
        assertEquals(4, preset.countInClicks)
        assertNull(preset.activeLoopPointId)
        assertEquals(ClickSound.CLICK, preset.clickSound)
    }

    @Test
    fun activeLoopPointIdRoundTrips() = runBlocking {
        val loopId = database.loopPointDao().insert(LoopPoint(trackId = 1, label = "A", positionA = 0, positionB = 1000))
        dao.insert(Preset(trackId = 1, activeLoopPointId = loopId, speed = 0.5f))
        val preset = dao.getByTrack(1) ?: error("expected preset")
        assertEquals(loopId, preset.activeLoopPointId)
        assertEquals(0.5f, preset.speed, 0.001f)
    }
}
