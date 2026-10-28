package io.github.rohmanhakim.tracktrainer.cache

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe set of cache keys that must never be evicted (Phase 7 pin semantics). */
class PinnedKeys {
    private val keys = ConcurrentHashMap.newKeySet<String>()

    fun contains(key: String): Boolean = keys.contains(key)

    fun replaceWith(keys: Collection<String>) {
        this.keys.clear()
        this.keys.addAll(keys)
    }

    val size: Int get() = keys.size
}