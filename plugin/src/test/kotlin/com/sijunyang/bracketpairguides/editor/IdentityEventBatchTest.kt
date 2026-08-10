package com.sijunyang.bracketpairguides.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class IdentityEventBatchTest {
    @Test
    fun `repeated requests for one value share one callback`() {
        val fixture = Fixture()
        val value = Any()

        repeat(10) { fixture.batch.request(value) }

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

        fixture.batch.request(first)
        fixture.batch.request(second)
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
        fixture.batch.request(removed)
        fixture.batch.request(retained)
        fixture.batch.remove(removed)

        fixture.runNext()

        assertEquals(listOf(retained), fixture.consumed)
        fixture.batch.request(removed)
        fixture.batch.clear()
        fixture.runNext()
        assertEquals(listOf(retained), fixture.consumed)
    }

    @Test
    fun `request during flush is deferred to the next batch`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val first = Any()
        val second = Any()
        val consumed = ArrayList<Any>()
        lateinit var batch: IdentityEventBatch<Any>
        batch = IdentityEventBatch(
            schedule = scheduled::addLast,
            consume = { value ->
                consumed += value
                if (value === first) batch.request(second)
            },
        )

        batch.request(first)
        scheduled.removeFirst().invoke()

        assertEquals(listOf(first), consumed)
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().invoke()
        assertEquals(listOf(first, second), consumed)
    }

    private class Fixture {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = ArrayList<Any>()
        val batch = IdentityEventBatch<Any>(
            schedule = scheduled::addLast,
            consume = consumed::add,
        )

        fun runNext() {
            scheduled.removeFirst().invoke()
        }
    }

    private data class EqualValue(val value: Int)
}
