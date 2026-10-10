package com.vymalo.keycloak.webhook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropOldestBufferTest {

    private fun DropOldestBuffer<String>.drain() = generateSequence { poll(0) }.toList()

    @Test
    fun `first in, first out`() {
        val buffer = DropOldestBuffer<String>(3)
        listOf("a", "b", "c").forEach { assertNull(buffer.add(it)) }
        assertEquals(listOf("a", "b", "c"), buffer.drain())
    }

    @Test
    fun `when full, the oldest makes room and is handed back`() {
        val buffer = DropOldestBuffer<String>(2)
        buffer.add("a")
        buffer.add("b")
        assertEquals("a", buffer.add("c"))
        assertEquals(listOf("b", "c"), buffer.drain())
    }

    @Test
    fun `requeued messages go back to the front in their order`() {
        val buffer = DropOldestBuffer<String>(5)
        buffer.add("c")
        assertEquals(emptyList(), buffer.requeue(listOf("a", "b")))
        assertEquals(listOf("a", "b", "c"), buffer.drain())
    }

    @Test
    fun `requeued messages that don't fit are dropped, oldest first`() {
        val buffer = DropOldestBuffer<String>(3)
        buffer.add("d")
        buffer.add("e")
        assertEquals(listOf("a", "b"), buffer.requeue(listOf("a", "b", "c")))
        assertEquals(listOf("c", "d", "e"), buffer.drain())
    }

    @Test
    fun `poll waits for a message, but not forever`() {
        val buffer = DropOldestBuffer<String>(1)
        val started = System.nanoTime()
        assertNull(buffer.poll(100))
        assertTrue(System.nanoTime() - started >= 90_000_000, "should have waited about 100ms")

        Thread { Thread.sleep(50); buffer.add("late") }.start()
        assertEquals("late", buffer.poll(5_000))
    }
}
