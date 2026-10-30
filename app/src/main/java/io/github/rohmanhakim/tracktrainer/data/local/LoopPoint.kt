package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One named A/B loop point stored for a track. */
@Entity(
    tableName = "loop_points",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("trackId")],
)
data class LoopPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val label: String,
    /** Start position in milliseconds (inclusive). */
    val positionA: Long,
    /** End position in milliseconds (exclusive). */
    val positionB: Long,
)
