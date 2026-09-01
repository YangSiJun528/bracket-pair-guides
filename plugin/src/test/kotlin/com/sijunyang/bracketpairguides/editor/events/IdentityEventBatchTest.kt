package com.sijunyang.bracketpairguides.editor.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.ArrayDeque

class IdentityEventBatchTest {
    @Test
    fun `repeated requests for one value share one callback`() {
        val fixture = Fixture()
        val value = Any()

        repeat(10) { fixture.batch.request(value) }

        assertThat(fixture.scheduled).hasSize(1)
        assertThat(fixture.consumed).isEmpty()
        fixture.runNext()
        assertThat(fixture.consumed).containsExactly(value)
    }

    @Test
    fun `distinct equal values retain identity`() {
        val fixture = Fixture()
        val first = EqualValue(1)
        val second = EqualValue(1)

        fixture.batch.request(first)
        fixture.batch.request(second)
        fixture.runNext()

        assertThat(fixture.consumed).hasSize(2)
        assertThat(fixture.consumed).anyMatch { it === first }
        assertThat(fixture.consumed).anyMatch { it === second }
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

        assertThat(fixture.consumed).containsExactly(retained)
        fixture.batch.request(removed)
        fixture.batch.clear()
        fixture.runNext()
        assertThat(fixture.consumed).containsExactly(retained)
    }

    @Test
    fun `request during flush is deferred to the next batch`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val first = Any()
        val second = Any()
        val consumed = ArrayList<Any>()
        lateinit var batch: IdentityEventBatch<Any>
        batch =
            IdentityEventBatch(
                schedule = scheduled::addLast,
                consume = { value ->
                    consumed += value
                    if (value === first) batch.request(second)
                },
            )

        batch.request(first)
        scheduled.removeFirst().invoke()

        assertThat(consumed).containsExactly(first)
        assertThat(scheduled).hasSize(1)
        scheduled.removeFirst().invoke()
        assertThat(consumed).containsExactly(first, second)
    }

    private class Fixture {
        val scheduled = ArrayDeque<() -> Unit>()
        val consumed = ArrayList<Any>()
        val batch =
            IdentityEventBatch<Any>(
                schedule = scheduled::addLast,
                consume = consumed::add,
            )

        fun runNext() {
            scheduled.removeFirst().invoke()
        }
    }

    private data class EqualValue(val value: Int)
}
