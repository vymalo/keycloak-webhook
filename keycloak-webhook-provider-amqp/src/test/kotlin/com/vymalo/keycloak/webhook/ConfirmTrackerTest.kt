package com.vymalo.keycloak.webhook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmTrackerTest {

    private var now = 1_000L
    private fun tracker(capacity: Int = 10) = ConfirmTracker<String>(capacity) { now }

    /** Reserves a slot and records [item] as sent, like the publisher does. */
    private fun ConfirmTracker<String>.send(seqNo: Long, item: String) {
        assertTrue(reserve(0))
        sent(seqNo, item)
    }

    @Test
    fun `slots run out at capacity and come back on confirm`() {
        val tracker = tracker(capacity = 2)
        tracker.send(1, "a")
        tracker.send(2, "b")
        assertFalse(tracker.reserve(0))

        assertEquals(1, tracker.confirmed(1, multiple = false))
        assertTrue(tracker.reserve(0))
    }

    @Test
    fun `a multiple confirm settles everything up to the tag`() {
        val tracker = tracker()
        (1L..4L).forEach { tracker.send(it, "m$it") }

        assertEquals(3, tracker.confirmed(3, multiple = true))
        assertEquals(listOf("m4"), tracker.abandon())
    }

    @Test
    fun `refused messages are handed back oldest first`() {
        val tracker = tracker()
        (1L..3L).forEach { tracker.send(it, "m$it") }

        assertEquals(listOf("m1", "m2"), tracker.refused(2, multiple = true))
        assertEquals(listOf("m3"), tracker.refused(3, multiple = false))
        assertTrue(tracker.isEmpty)
    }

    @Test
    fun `abandoning frees every slot, and late confirms change nothing`() {
        val tracker = tracker(capacity = 2)
        tracker.send(1, "a")
        tracker.send(2, "b")

        assertEquals(listOf("a", "b"), tracker.abandon())
        assertEquals(0, tracker.confirmed(2, multiple = true))
        // Exactly the two slots came back, not more: a third reserve still fails.
        assertTrue(tracker.reserve(0))
        assertTrue(tracker.reserve(0))
        assertFalse(tracker.reserve(0))
    }

    @Test
    fun `the oldest unconfirmed message's wait is what the watchdog watches`() {
        val tracker = tracker()
        assertNull(tracker.oldestWaitMs())
        tracker.send(1, "a")
        now += 300
        tracker.send(2, "b")
        now += 200
        assertEquals(500, tracker.oldestWaitMs())

        tracker.confirmed(1, multiple = false)
        assertEquals(200, tracker.oldestWaitMs())
    }

    @Test
    fun `a failed publish is forgotten and its slot returned`() {
        val tracker = tracker(capacity = 1)
        tracker.send(7, "a")
        assertEquals("a", tracker.forget(7))
        assertNull(tracker.forget(7))
        assertTrue(tracker.reserve(0))
    }
}
