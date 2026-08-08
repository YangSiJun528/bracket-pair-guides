package com.sijunyang.bracketpairguides.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class IdentityEventBatcherTest {
    @Test
    fun `repeated requests for one value share one callback`() {
        val fixture = Fixture()
        val value = Any()

        repeat(10) { fixture.batcher.request(value) }

        assertEquals(1, fixture.scheduled.size)
        assertTrue(fixture.consumed.isEmpty())
        fixture.runNext()
        assertEquals(listOf(value), fixture.consumed)
    }

    @Test
    fun `distinct equal values retain identity`() {
        val fixture = Fixture()
        val first = EqualValue(1)
        val second = EqualValue(1)

        fixture.batcher.request(first)
        fixture.batcher.request(second)
        fixture.runNext()

        assertEquals(2, fixture.consumed.size)
        assertTrue(fixture.consumed.any { it === first })
        assertTrue(fixture.consumed.any { it === second })
    }

    @Test
    fun `removed and cleared values are not consumed`() {
        val fixture = Fixture()
        val removed = Any()
        val retained = Any()
        fixture.batcher.request(removed)
        fixture.batcher.request(retained)
        fixture.batcher.remove(removed)

        fixture.runNext()

        assertEquals(listOf(retained), fixture.consumed)
        fixture.batcher.request(removed)
        fixture.batcher.clear()
        fixture.runNext()
        assertEquals(listOf(retained), fixture.consumed)
    }

    @Test
    fun `request during flush is deferred to the next batch`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val first = Any()
        val second = Any()
        val consumed = ArrayList<Any>()
        lateinit var batcher: IdentityEventBatcher<Any>
        batcher = IdentityEventBatcher(
            schedule = scheduled::addLast,
            consume = { value ->
                consumed += value
                if (value === first) batcher.request(second)
            },
        )

        batcher.request(first)
        scheduled.removeFirst().invoke()

        assertEquals(listOf(first), consumed)
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()
        assertEquals(listOf(first, second), consumed)
    }

    private class Fixture {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = ArrayList<Any>()
        val batcher = IdentityEventBatcher<Any>(
            schedule = scheduled::addLast,
            consume = consumed::add,
        )

        fun runNext() {
            scheduled.removeFirst().invoke()
        }
    }

    private data class EqualValue(val value: Int)
}
