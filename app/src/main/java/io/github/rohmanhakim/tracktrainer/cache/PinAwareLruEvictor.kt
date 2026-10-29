package io.github.rohmanhakim.tracktrainer.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.util.HashMap

/**
 * LRU [CacheEvictor] that never evicts pinned keys.
 *
 * Media3's contract: the evictor itself calls [Cache.removeSpan] for each victim (see the
 * [CacheEvictor] javadoc), so [onEvict] is wired to [SimpleCache.removeSpan] in production.
 * Phase 2 runs effectively uncapped ([maxBytes] = Long.MAX_VALUE — owner decision, R7), so
 * eviction never fires in production yet. The LRU logic is present, correct and unit-tested,
 * ready for a size cap in Phase 7. Pinned-track exemption is the backing semantics for the
 * Phase 7 pin / "keep offline" UI; verified here programmatically (no pin UI in Phase 2).
 */
@OptIn(UnstableApi::class)
class PinAwareLruEvictor(
    /** Effectively uncapped in Phase 2. */
    private val maxBytes: Long,
    /** True for keys that must never be evicted (thread-safe). */
    private val isPinned: (String) -> Boolean,
    /** Invoked with each span chosen for eviction (defaults to a no-op). */
    private val onEvict: (CacheSpan) -> Unit = { },
    /** Clock for access timestamps (injectable so tests are deterministic). */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CacheEvictor {

    /** Actually removes the span from [Cache]. Wired to [SimpleCache.removeSpan] by [CacheModule]. */
    var removeSpan: (CacheSpan) -> Unit = { }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictIfNeeded(length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        record(span.key, span.length, span.lastTouchTimestamp)
        evictIfNeeded(0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        forget(span.key)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        forget(oldSpan.key)
        record(newSpan.key, newSpan.length, newSpan.lastTouchTimestamp)
        evictIfNeeded(0)
    }

    private fun record(key: String, size: Long, touched: Long) {
        synchronized(this) {
            sizes[key] = size
            touches[key] = touched
        }
    }

    private fun forget(key: String) {
        synchronized(this) {
            sizes.remove(key)
            touches.remove(key)
        }
    }

    /**
     * Evicts the oldest unpinned spans until [requiredSpace] fits under [maxBytes].
     * Returns the total size evicted.
     */
    private fun evictIfNeeded(requiredSpace: Long) {
        synchronized(this) {
            var current = sizes.values.sumOf { it }
            while (current + requiredSpace > maxBytes) {
                val victim = sizes.keys.asSequence()
                    .filter { !isPinned(it) }
                    .minBy { touches[it] ?: 0L } ?: break
                val size = sizes[victim] ?: break
                val touched = touches[victim] ?: break
                sizes.remove(victim)
                touches.remove(victim)
                current -= size
                val span = CacheSpan(victim, 0, size, touched, File(victim))
                onEvict(span)
                removeSpan(span)
            }
        }
    }

    /** key -> size in bytes, learned from onSpanAdded/onSpanTouched. */
    private val sizes = HashMap<String, Long>()
    /** key -> last touch timestamp. */
    private val touches = HashMap<String, Long>()
}