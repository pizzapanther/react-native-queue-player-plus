package com.margelo.nitro.queueplayer

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe registry of event-listener callbacks, keyed by stable
 * numeric IDs so individual subscribers can dispose without
 * disturbing the others.
 *
 * `CopyOnWriteArrayList` gives lock-free iteration during concurrent
 * add/remove (fire rounds + register/unregister can happen on
 * different threads); `AtomicLong` produces collision-free ids
 * without an explicit lock.
 *
 * Mirrors the iOS `ListenerRegistry.swift`.
 */
internal class ListenerRegistry<T> {
    private data class Entry<T>(val id: Long, val callback: T)

    private val entries = CopyOnWriteArrayList<Entry<T>>()
    private val nextId = AtomicLong(0)

    /** Register a callback and return its stable ID for later removal. */
    fun add(callback: T): Long {
        val id = nextId.incrementAndGet()
        entries.add(Entry(id, callback))
        return id
    }

    /** Remove the callback with the given ID. Returns true if found. */
    fun remove(id: Long): Boolean {
        val entry = entries.firstOrNull { it.id == id } ?: return false
        // Linear scan acceptable; listener counts are O(few).
        return entries.remove(entry)
    }

    /** Remove all registered callbacks. */
    fun clear() = entries.clear()

    /** Invoke `action` for every registered callback. CopyOnWrite makes
     * iteration safe under concurrent mutation. */
    fun forEach(action: (T) -> Unit) {
        for (entry in entries) action(entry.callback)
    }

    /** True when no callbacks are registered. Handy for skipping
     * per-tick work (progress observer body) when nothing's listening. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * True when the given id is still registered. Used by deferred
     * auto-fire paths (e.g. `onSkipCapabilityChange`) to skip the
     * callback when the subscriber disposed between registration
     * and the deferred fire — avoids calling into an unmounted
     * React component's `setSnapshot`.
     */
    fun contains(id: Long): Boolean = entries.any { it.id == id }
}
