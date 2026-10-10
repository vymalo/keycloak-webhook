package com.vymalo.keycloak.webhook

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Messages sent on one channel that the broker hasn't confirmed yet, by publish sequence number.
 *
 * Use one tracker per channel: sequence numbers start again with every channel, so a late
 * confirm from a channel that already died can only reach its own, abandoned tracker.
 * A semaphore caps how many messages wait for a confirm at once; every way out
 * (confirmed, refused, forgotten, abandoned) hands the slot back exactly once.
 *
 * Confirms arrive on the connection's thread, everything else happens on the publisher's.
 */
internal class ConfirmTracker<T>(capacity: Int, private val clock: () -> Long = System::currentTimeMillis) {

    private class Sent<T>(val item: T, val atMs: Long)

    private val slots = Semaphore(capacity)
    private val unconfirmed = ConcurrentSkipListMap<Long, Sent<T>>()

    /** Takes a slot for one more message, waiting up to [timeoutMs]. False if all slots stayed taken. */
    fun reserve(timeoutMs: Long): Boolean = slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)

    /** Gives back a slot taken with [reserve] that ended up unused. */
    fun unreserve() = slots.release()

    /** Records [item] as sent with sequence number [seqNo], in the slot reserved for it. */
    fun sent(seqNo: Long, item: T) {
        unconfirmed[seqNo] = Sent(item, clock())
    }

    /** The broker has [tag] (and, if [multiple], everything before it). Returns how many were settled. */
    fun confirmed(tag: Long, multiple: Boolean): Int = settle(tag, multiple).size

    /** The broker refused [tag] (and, if [multiple], everything before it). Returns them, oldest first. */
    fun refused(tag: Long, multiple: Boolean): List<T> = settle(tag, multiple)

    /** Publishing [seqNo] failed before it reached the broker. Returns its item, if it was tracked. */
    fun forget(seqNo: Long): T? = take(listOf(seqNo)).firstOrNull()

    /** The channel is gone: everything still unconfirmed, oldest first, to be sent again elsewhere. */
    fun abandon(): List<T> = take(unconfirmed.keys.toList())

    /** How long the oldest unconfirmed message has been waiting, or null if none is. */
    fun oldestWaitMs(): Long? = unconfirmed.firstEntry()?.let { clock() - it.value.atMs }

    val isEmpty: Boolean get() = unconfirmed.isEmpty()

    private fun settle(tag: Long, multiple: Boolean): List<T> =
        take(if (multiple) unconfirmed.headMap(tag, true).keys.toList() else listOf(tag))

    /**
     * Removes [seqNos] one by one: a key can only be removed once, so an ack racing with
     * [abandon] can never hand back the same slot twice.
     */
    private fun take(seqNos: List<Long>): List<T> {
        val items = seqNos.mapNotNull { unconfirmed.remove(it)?.item }
        slots.release(items.size)
        return items
    }
}
