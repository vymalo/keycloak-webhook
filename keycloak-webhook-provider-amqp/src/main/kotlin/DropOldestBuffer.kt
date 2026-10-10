package com.vymalo.keycloak.webhook

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * First-in, first-out buffer that never blocks the caller and never grows past [capacity].
 * When it's full, the oldest message makes room: during a long outage the newest events
 * are the ones worth keeping. Messages put back with [requeue] are older than anything
 * in the buffer, so they are the first to go when there's no room.
 */
internal class DropOldestBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    init {
        require(capacity > 0) { "capacity must be greater than 0" }
    }

    /** Adds [item] at the back. Returns the message dropped to make room, or null. */
    fun add(item: T): T? = lock.withLock {
        val dropped = if (items.size >= capacity) items.removeFirst() else null
        items.addLast(item)
        notEmpty.signal()
        dropped
    }

    /** Puts [older] (oldest first) back at the front in its order. Returns what didn't fit. */
    fun requeue(older: List<T>): List<T> = lock.withLock {
        val kept = older.takeLast((capacity - items.size).coerceAtLeast(0))
        kept.asReversed().forEach(items::addFirst)
        if (kept.isNotEmpty()) notEmpty.signalAll()
        older.dropLast(kept.size)
    }

    /** The next message, waiting up to [timeoutMs] for one to arrive; null if none did. */
    fun poll(timeoutMs: Long): T? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (items.isEmpty()) {
            if (remaining <= 0) return null
            remaining = notEmpty.awaitNanos(remaining)
        }
        items.removeFirst()
    }

    val size: Int get() = lock.withLock { items.size }
}
