package com.sijunyang.bracketpairguides.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BracketStackTest {
    @Test
    fun `pairs nested brackets with stable depths`() {
        val stack = BracketStack<Char, String>()

        stack.open("main", '(', ')', 0, 1, 0)
        stack.open("main", '[', ']', 1, 1, 0)

        assertEquals(1, stack.close("main", ']')?.open?.depth)
        assertEquals(0, stack.close("main", ')')?.open?.depth)
    }

    @Test
    fun `ignores an unrelated closing token without losing openers`() {
        val stack = BracketStack<Char, String>()

        stack.open("main", '(', ')', 0, 1, 0)
        assertNull(stack.close("main", '}'))

        assertEquals('(', stack.close("main", ')')?.open?.token)
    }

    @Test
    fun `recovers an outer pair past an unclosed inner bracket`() {
        val stack = BracketStack<Char, String>()

        stack.open("main", '{', '}', 0, 1, 0)
        stack.open("main", '(', ')', 1, 1, 0)

        assertEquals('{', stack.close("main", '}')?.open?.token)
        assertNull(stack.close("main", ')'))
    }

    @Test
    fun `keeps embedded language stacks independent`() {
        val stack = BracketStack<Char, String>()

        stack.open("host", '{', '}', 0, 1, 0)
        stack.open("embedded", '(', ')', 1, 1, 0)

        assertEquals('{', stack.close("host", '}')?.open?.token)
        assertEquals('(', stack.close("embedded", ')')?.open?.token)
    }

    @Test
    fun `accepts multiple closing tokens for one opening token`() {
        val stack = BracketStack<Char, String>()

        stack.open(
            group = "main",
            token = '<',
            expectedCloses = setOf('>', '|'),
            offset = 0,
            tokenLength = 1,
            line = 0,
        )

        assertEquals('<', stack.close("main", '|')?.open?.token)
    }

    @Test(timeout = 10_000)
    fun `many unrelated closers do not rescan a deep stack`() {
        val depth = 50_000
        val stack = BracketStack<Char, String>()
        repeat(depth) { offset ->
            stack.open("main", '(', ')', offset, 1, 0)
        }

        repeat(depth) {
            assertNull(stack.close("main", ']'))
        }
        repeat(depth) { expectedDepth ->
            assertEquals(depth - expectedDepth - 1, stack.close("main", ')')?.open?.depth)
        }
    }
}
