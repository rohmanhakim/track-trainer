package io.github.rohmanhakim.tracktrainer.player

/**
 * Duration of one video frame in milliseconds.
 *
 * Uses the container's frame rate when known, else falls back to ~33 ms (≈30 fps) —
 * good enough for technique study (R14).
 */
fun frameDurationMs(frameRate: Float): Long = when {
    frameRate > 0f -> (1000.0 / frameRate).toLong().coerceAtLeast(1)
    else -> 33
}
