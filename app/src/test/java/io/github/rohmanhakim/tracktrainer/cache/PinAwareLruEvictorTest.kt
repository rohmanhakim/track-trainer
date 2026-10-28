package io.github.rohmanhakim.tracktrainer.cache

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.File
import java.util.NavigableSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinAwareLruEvictorTest {

    private var clock = 0L
    private lateinit var evictor: PinAwareLruEvictor
    private val removedSpans = mutableListOf<CacheSpan>()

    private val dummyCache = object : Cache {
        override fun getUid(): Long = 0
        override fun release() {}
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> {
            @Suppress("UNCHECKED_CAST")
            val empty = emptySet<CacheSpan>() as NavigableSet<CacheSpan>
            return empty
        }
        override fun removeListener(key: String, listener: Cache.Listener) {}
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> {
            @Suppress("UNCHECKED_CAST")
            val empty = emptySet<CacheSpan>() as NavigableSet<CacheSpan>
            return empty
        }
        override fun getKeys(): Set<String> = emptySet()
        override fun getCacheSpace(): Long = 0
        override fun releaseHoleSpan(span: CacheSpan) {}
        override fun removeResource(key: String) {}
        override fun removeSpan(span: CacheSpan) {}
        override fun isCached(key: String, position: Long, length: Long): Boolean = false
        override fun getCachedLength(key: String, position: Long, length: Long): Long = 0
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = 0
        override fun getContentMetadata(key: String): Nothing =
            error("DummyCache.getContentMetadata not used by the evictor tests")
        override fun startReadWrite(key: String, position: Long, length: Long): Nothing =
            error("DummyCache.startReadWrite not used by the evictor tests")
        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): Nothing =
            error("DummyCache.startReadWriteNonBlocking not used by the evictor tests")
        override fun startFile(key: String, position: Long, length: Long): Nothing =
            error("DummyCache.startFile not used by the evictor tests")
        override fun commitFile(file: File, length: Long) {}
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {}
    }

    @Before
    fun setUp() {
        clock = 0
        removedSpans.clear()
        evictor = PinAwareLruEvictor(
            maxBytes = 250,
            isPinned = { false },
            onEvict = { removedSpans.add(it) },
            clock = { clock },
        )
    }

    private fun span(key: String, size: Long): CacheSpan {
        clock += 10
        return CacheSpan(key, 0, size, clock, File("/tmp/$key"))
    }

    private fun add(key: String, size: Long) {
        evictor.onSpanAdded(dummyCache, span(key, size))
    }

    private fun touch(key: String, newSize: Long) {
        val current = span(key, newSize)
        evictor.onSpanTouched(dummyCache, CacheSpan(key, 0, 0, 0, File("/tmp/$key")), current)
    }

    @Test
    fun evictsOldestUnpinnedFirst() {
        add("k1", 100) // 100 total, no eviction
        add("k2", 200) // 300 total > 250 -> evict k1 (oldest)
        assertEquals(listOf("k1"), removedSpans.map { it.key })
        assertEquals(100L, removedSpans[0].length)

        add("k3", 60) // 260 total > 250 -> evict k2 (now oldest)
        assertEquals(listOf("k1", "k2"), removedSpans.map { it.key })
    }

    @Test
    fun nothingEvictedWhenUnderCap() {
        add("k1", 100)
        add("k2", 100)
        assertTrue(removedSpans.isEmpty())
    }

    @Test
    fun pinnedKeysAreNeverEvicted() {
        evictor = PinAwareLruEvictor(
            maxBytes = 250,
            isPinned = { it == "k2" },
            onEvict = { removedSpans.add(it) },
            clock = { clock },
        )
        add("k1", 100) // t=10, total 100
        add("k2", 200) // t=20, total 300 > 250 -> k1 evicted (only unpinned candidate); k2 pinned stays
        assertEquals(listOf("k1"), removedSpans.map { it.key })

        add("k3", 60) // t=30, total 260 > 250 -> k3 itself evicted; k2 still pinned
        assertEquals(listOf("k1", "k3"), removedSpans.map { it.key })
        assertTrue(removedSpans.none { it.key == "k2" })
    }

    @Test
    fun touchUpdatesRecency() {
        add("k1", 100) // t=10
        add("k2", 100) // t=20, 200 total under cap
        touch("k1", 100) // k1 now newest (t=30)
        add("k3", 100) // 300 > 250 -> evict k2 (oldest touch t=20), not k1
        assertEquals(listOf("k2"), removedSpans.map { it.key })
    }

    @Test
    fun removalStopsTracking() {
        add("k1", 100)
        add("k2", 100) // 200 under cap
        evictor.onSpanRemoved(dummyCache, span("k1", 100))
        add("k3", 200) // 300 > 250 -> only k2 is tracked -> evict k2
        assertEquals(listOf("k2"), removedSpans.map { it.key })
    }
}