package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Per-track playback settings, restored when a track is opened (P3-4).
 *
 * Columns introduced by phases 4–6 (count-in, metronome, boost) are part of the
 * V1 schema but populated later; they ship with safe defaults here.
 */
@Entity(
    tableName = "presets",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LoopPoint::class,
            parentColumns = ["id"],
            childColumns = ["activeLoopPointId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("trackId"),
        Index("activeLoopPointId"),
    ],
)
data class Preset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** The active loop point, or null when no loop is saved for the track. */
    val activeLoopPointId: Long? = null,
    /** Whether the A-B loop was armed when last saved (P3-4 exact restore). */
    val loopEnabled: Boolean = false,
    /** Playback speed multiplier (1.0 = normal speed). */
    val speed: Float = 1f,
    /** Semitone transpose. Reserved for a future phase; unused in V1. */
    val transpose: Int = 0,
    /** Beats per minute. 0 means unset. Populated by Phase 5 tap tempo. */
    val bpm: Float = 0f,
    /** Click offset in milliseconds applied to the tapped tempo grid. */
    val tapOffsetMs: Int = 0,
    /** Default fixed count-in clicks (default = 4 per the risk register). */
    val countInClicks: Int = 4,
    /** Count-in bars. 0 means clicks mode. */
    val countInBars: Int = 0,
    val clickSound: ClickSound = ClickSound.CLICK,
    val clickVolume: Float = 1f,
    /** Volume boost in dB. Populated by Phase 6. */
    val boostDb: Float = 0f,
)

/** Placeholder click sound options; wired up in Phase 4. */
enum class ClickSound { CLICK, BEEP }
