package io.github.rohmanhakim.tracktrainer.data.local

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration3to4Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    private lateinit var db: androidx.sqlite.db.SupportSQLiteDatabase

    @Before
    fun setUp() {
        db = helper.createDatabase(DB_NAME, 3)
        // Seed a track under the v3 schema; it must survive the 3→4 migration.
        db.execSQL(
            "INSERT INTO tracks (sourceType, sourceRef, title, durationMs) " +
                "VALUES ('LOCAL', 'local-ref', 'Track One', 120000)"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun migrateFrom3To4PreservesTracksAndAddsTables() {
        // Migrates `db` in place and asserts the resulting schema matches the
        // exported version-4 schema (throws otherwise).
        helper.runMigrationsAndValidate(db, 4, true, AppDatabase.MIGRATION_3_4)

        // The pre-existing track survived the migration.
        val cursor = db.query(SimpleSQLiteQuery("SELECT title FROM tracks WHERE title = 'Track One'"))
        assertTrue("track row should survive migration", cursor.moveToFirst())
        assertEquals("Track One", cursor.getString(0))
        cursor.close()

        // The new Phase 3 tables exist (querying a missing table throws).
        db.query(SimpleSQLiteQuery("SELECT * FROM loop_points LIMIT 0")).close()
        db.query(SimpleSQLiteQuery("SELECT * FROM presets LIMIT 0")).close()
    }

    companion object {
        private const val DB_NAME = "track-trainer.db"
    }
}
