package io.github.rohmanhakim.tracktrainer.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Migration 4→5 (P3-4): `presets` gains `loopEnabled`. Builds a real file-backed v4
 * database (DDL mirrors the exported 4.json), then lets Room open it at v5 so the
 * schema validator checks the migration against the expected v5 schema — the same
 * path an install-over-old-build takes on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppDatabaseMigration45Test {
    private lateinit var dbFile: File
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.application
        dbFile = context.getDatabasePath("migration-45-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        V4_DDL.forEach { raw.execSQL(it) }
        raw.execSQL(
            "INSERT INTO tracks (id, sourceType, sourceRef, title, durationMs, offline, pinned, needsRefresh, cacheBytes) " +
                "VALUES (1, 'LOCAL', 'content://test', 'Test Track', 1000, 0, 0, 0, 0)",
        )
        raw.execSQL("INSERT INTO loop_points (id, trackId, label, positionA, positionB) VALUES (7, 1, 'verse', 1000, 5000)")
        raw.execSQL(
            "INSERT INTO presets (id, trackId, activeLoopPointId, speed, transpose, bpm, tapOffsetMs, " +
                "countInClicks, countInBars, clickSound, clickVolume, boostDb) " +
                "VALUES (3, 1, 7, 0.75, 0, 0.0, 0, 4, 0, 'CLICK', 1.0, 0.0)",
        )
        raw.version = 4
        raw.close()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        dbFile.delete()
    }

    private fun openAtV5(): AppDatabase =
        Room.databaseBuilder(RuntimeEnvironment.application, AppDatabase::class.java, dbFile.path)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .build()

    @Test
    fun migration45_preservesRowsAndDefaultsLoopEnabledOff() = runBlocking {
        database = openAtV5()
        val preset = database.presetDao().getByTrack(1)
        assertNotNull("preset row must survive the migration", preset)
        // v4 row preserved as-is…
        assertEquals(0.75f, preset!!.speed, 0.001f)
        assertEquals(7L, preset.activeLoopPointId)
        // …and loopEnabled defaults to off: never auto-arm a loop on upgrade.
        assertFalse(preset.loopEnabled)
    }

    @Test
    fun migration45_newColumnRoundTrips() = runBlocking {
        database = openAtV5()
        val preset = database.presetDao().getByTrack(1) ?: error("expected preset")
        database.presetDao().update(preset.copy(loopEnabled = true))
        assertTrue(database.presetDao().getByTrack(1)!!.loopEnabled)
    }

    @Test
    fun migration45_isIdempotent_secondOpenNeedsNoMigration() = runBlocking {
        val first = openAtV5()
        assertNotNull(first.presetDao().getByTrack(1))
        first.close()
        database = openAtV5() // already v5 — clean open, no migration
        assertNotNull(database.presetDao().getByTrack(1))
    }

    /** v4 schema: tracks + loop_points + presets + indices, as exported in 4.json. */
    private companion object {
        val V4_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `streamUrl` TEXT, `title` TEXT NOT NULL, " +
                "`youtubeId` TEXT, `itag` TEXT, `durationMs` INTEGER NOT NULL, `thumbnailPath` TEXT, " +
                "`offline` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `needsRefresh` INTEGER NOT NULL, " +
                "`cacheBytes` INTEGER NOT NULL, `lastPlayedAt` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `loop_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`trackId` INTEGER NOT NULL, `label` TEXT NOT NULL, `positionA` INTEGER NOT NULL, " +
                "`positionB` INTEGER NOT NULL, " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_loop_points_trackId` ON `loop_points` (`trackId`)",
            "CREATE TABLE IF NOT EXISTS `presets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`trackId` INTEGER NOT NULL, `activeLoopPointId` INTEGER, `speed` REAL NOT NULL, " +
                "`transpose` INTEGER NOT NULL, `bpm` REAL NOT NULL, `tapOffsetMs` INTEGER NOT NULL, " +
                "`countInClicks` INTEGER NOT NULL, `countInBars` INTEGER NOT NULL, `clickSound` TEXT NOT NULL, " +
                "`clickVolume` REAL NOT NULL, `boostDb` REAL NOT NULL, " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`activeLoopPointId`) REFERENCES `loop_points`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_presets_trackId` ON `presets` (`trackId`)",
            "CREATE INDEX IF NOT EXISTS `index_presets_activeLoopPointId` ON `presets` (`activeLoopPointId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'bd8d52ebe1ea53c3166f305f203cfacb')",
        )
    }
}