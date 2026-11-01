package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Track::class, LoopPoint::class, Preset::class], version = 5, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun loopPointDao(): LoopPointDao
    abstract fun presetDao(): PresetDao

    companion object {
        const val NAME = "track-trainer.db"

        /** Non-destructive: adds the `itag` column (T02 stable cache key for YouTube tracks). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN itag TEXT")
            }
        }

        /** Non-destructive: adds `streamUrl` so cache-hit opens replay without re-extraction (T04, decision 1A). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN streamUrl TEXT")
            }
        }

        /** Non-destructive: adds the `loop_points` and `presets` tables (Phase 3 T01). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS loop_points (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        trackId INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        positionA INTEGER NOT NULL,
                        positionB INTEGER NOT NULL,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_loop_points_trackId ON loop_points (trackId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS presets (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        trackId INTEGER NOT NULL,
                        activeLoopPointId INTEGER,
                        speed REAL NOT NULL DEFAULT 1.0,
                        transpose INTEGER NOT NULL DEFAULT 0,
                        bpm REAL NOT NULL DEFAULT 0.0,
                        tapOffsetMs INTEGER NOT NULL DEFAULT 0,
                        countInClicks INTEGER NOT NULL DEFAULT 4,
                        countInBars INTEGER NOT NULL DEFAULT 0,
                        clickSound TEXT NOT NULL DEFAULT 'CLICK',
                        clickVolume REAL NOT NULL DEFAULT 1.0,
                        boostDb REAL NOT NULL DEFAULT 0.0,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE,
                        FOREIGN KEY(activeLoopPointId) REFERENCES loop_points(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_presets_trackId ON presets (trackId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_presets_activeLoopPointId ON presets (activeLoopPointId)")
            }
        }

        /**
         * Non-destructive: adds `loopEnabled` so preset restore is exact — loop-off stays
         * off across reopens (P3-4, owner-approved schema amendment 2026-11-01). Existing
         * rows default to 0 (never auto-arm a loop the owner can't see coming).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE presets ADD COLUMN loopEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}