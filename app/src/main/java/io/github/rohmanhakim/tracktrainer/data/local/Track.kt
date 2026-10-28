package io.github.rohmanhakim.tracktrainer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Where a track's media comes from */
enum class SourceType { LOCAL, YOUTUBE, DIRECT }

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: SourceType,
    val sourceRef: String,
    val title: String,
    val youtubeId: String? = null,
    /** itag of the selected progressive stream (YouTube only) — part of the stable cache key. */
    val itag: String? = null,
    val durationMs: Long = 0L,
    val thumbnailPath: String? = null,
    val offline: Boolean = false,
    val pinned: Boolean = false,
    val needsRefresh: Boolean = false,
    val cacheBytes: Long = 0L,
    val lastPlayedAt: Long? = null,
)