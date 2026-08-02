package com.sijunyang.bracketpairguides.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BracketStackTest {
    @Test
    fun `pairs nested brackets with stable depths`() {
        val stack = BracketStack<Char, String>()

        stack.open("main", setOf(')'), 0, 1, 0)
        stack.open("main", setOf(']'), 1, 1, 0)

        assertEquals(1, stack.close("main", ']')?.open?.depth)
        assertEquals(0, stack.close("main", ')')?.open?.depth)
    }

    @Test
    fun `ignores an unrelated closing token without losing openers`() {
        val stack = BracketStack<Char, String>()

        stack.open("main", setOf(')'), 0, 1, 0)
        assertNull(stack.close("main", '}'))

        assertEquals(0, stack.close("main", ')')?.open?.offset)
    }

    @Test
    fun `recovers an outer pair past an unclosed inner bracket`() {
        val stack = BracketStack<Char, String>()

        stack.open("main", setOf('}'), 0, 1, 0)
        stack.open("main", setOf(')'), 1, 1, 0)

        assertEquals(0, stack.close("main", '}')?.open?.offset)
        assertNull(stack.close("main", ')'))
    }

    @Test
    fun `keeps embedded language stacks independent`() {
        val stack = BracketStack<Char, String>()

        stack.open("host", setOf('}'), 0, 1, 0)
        stack.open("embedded", setOf(')'), 1, 1, 0)

        assertEquals(0, stack.close("host", '}')?.open?.offset)
        assertEquals(1, stack.close("embedded", ')')?.open?.offset)
    }

    @Test
    fun `accepts multiple closing tokens for one opening token`() {
        val stack = BracketStack<Char, String>()

        stack.open(
            group = "main",
            expectedCloses = setOf('>', '|'),
            offset = 0,
            tokenLength = 1,
            line = 0,
        )

        assertEquals(0, stack.close("main", '|')?.open?.offset)
    }

    @Test(timeout = 10_000)
    fun `many unrelated closers do not rescan a deep stack`() {
        val depth = 50_000
        val stack = BracketStack<Char, String>()
        repeat(depth) { offset ->
            stack.open("main", setOf(')'), offset, 1, 0)
        }

        repeat(depth) {
            assertNull(stack.close("main", ']'))
        }
        repeat(depth) { expectedDepth ->
            assertEquals(depth - expectedDepth - 1, stack.close("main", ')')?.open?.depth)
        }
    }
}
