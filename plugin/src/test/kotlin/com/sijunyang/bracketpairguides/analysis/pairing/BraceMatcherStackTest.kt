package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BraceMatcherStackTest {
    @Test
    fun `pairs nested tokens with stable depths`() {
        val stack = BraceMatcherStack<Char, String>()

        stack.open("main", '(', null, 0, 1, 0)
        stack.open("main", '[', null, 1, 1, 0)

        assertEquals(1, stack.close("main", ']', null, false, ::isPair)?.open?.depth)
        assertEquals(0, stack.close("main", ')', null, false, ::isPair)?.open?.depth)
    }

    @Test
    fun `same offset remains live until every matcher group removes it`() {
        val stack = BraceMatcherStack<Char, String>(trackedOpenOffset = 7)
        stack.open("round", '(', null, 7, 1, 0)
        stack.open("square", '[', null, 7, 1, 0)

        assertTrue(stack.containsOpenAt(7))
        stack.close("round", ')', null, false, ::isPair)
        assertTrue(stack.containsOpenAt(7))
        stack.close("square", ']', null, false, ::isPair)
        assertFalse(stack.containsOpenAt(7))
    }

    @Test
    fun `does not retain unrelated opener offsets when tracking a candidate`() {
        val stack = BraceMatcherStack<Char, String>(trackedOpenOffset = 7)
        stack.open("round", '(', null, 3, 1, 0)
        stack.open("round", '(', null, 7, 1, 0)
        stack.open("round", '(', null, 9, 1, 0)

        assertTrue(stack.containsOpenAt(7))
        stack.close("round", ')', null, false, ::isPair)
        assertTrue(stack.containsOpenAt(7))
        stack.close("round", ')', null, false, ::isPair)
        assertFalse(stack.containsOpenAt(7))
        assertFalse(stack.containsOpenAt(3))
        assertFalse(stack.containsOpenAt(9))
    }

    @Test
    fun `ignores an unrelated closer without losing openers`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '(', null, 0, 1, 0)

        assertNull(stack.close("main", '}', null, false, ::isPair))
        assertEquals(0, stack.close("main", ')', null, false, ::isPair)?.open?.offset)
    }

    @Test
    fun `recovers an outer pair past an unclosed inner token`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '{', null, 0, 1, 0)
        stack.open("main", '(', null, 1, 1, 0)

        assertEquals(0, stack.close("main", '}', null, false, ::isPair)?.open?.offset)
        assertNull(stack.close("main", ')', null, false, ::isPair))
    }

    @Test
    fun `structural pair recovers past unmatched regular openers`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '{', null, 0, 1, 0, structural = true)
        stack.open("main", '(', null, 1, 1, 0)
        stack.open("main", '[', null, 2, 1, 0)

        assertEquals(
            0,
            stack.close(
                "main",
                '}',
                null,
                false,
                ::isPair,
                ::isStructuralPair,
            )?.open?.offset,
        )
        assertNull(
            stack.close("main", ')', null, false, ::isPair, ::isStructuralPair),
        )
    }

    @Test
    fun `structural candidate has priority over a regular top candidate`() {
        val stack = BraceMatcherStack<Char, String>()
        val sharesClose = { left: Char, right: Char ->
            right == 'x' && (left == '{' || left == '(')
        }
        val structural = { left: Char, right: Char -> left == '{' && right == 'x' }
        stack.open("main", '{', null, 0, 1, 0, structural = true)
        stack.open("main", '(', null, 1, 1, 0)

        assertEquals(
            0,
            stack.close("main", 'x', null, false, sharesClose, structural)?.open?.offset,
        )
    }

    @Test
    fun `regular pair cannot end inside a structural pair`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '(', null, 0, 1, 0)
        stack.open("main", '{', null, 1, 1, 0, structural = true)

        assertNull(
            stack.close("main", ')', null, false, ::isPair, ::isStructuralPair),
        )
        assertEquals(
            1,
            stack.close(
                "main",
                '}',
                null,
                false,
                ::isPair,
                ::isStructuralPair,
            )?.open?.offset,
        )
        assertEquals(
            0,
            stack.close(
                "main",
                ')',
                null,
                false,
                ::isPair,
                ::isStructuralPair,
            )?.open?.offset,
        )
    }

    @Test
    fun `unmatched structural opener conservatively blocks a regular match`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '(', null, 0, 1, 0)
        stack.open("main", '{', null, 1, 1, 0, structural = true)

        assertNull(
            stack.close("main", ')', null, false, ::isPair, ::isStructuralPair),
        )
    }

    @Test
    fun `regular pair can be fully contained by a structural pair`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '{', null, 0, 1, 0, structural = true)
        stack.open("main", '(', null, 1, 1, 0)

        assertEquals(
            1,
            stack.close(
                "main",
                ')',
                null,
                false,
                ::isPair,
                ::isStructuralPair,
            )?.open?.offset,
        )
        assertEquals(
            0,
            stack.close(
                "main",
                '}',
                null,
                false,
                ::isPair,
                ::isStructuralPair,
            )?.open?.offset,
        )
    }

    @Test
    fun `non-structural closer skips structural candidate scans`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("main", '{', null, 0, 1, 0, structural = true)
        stack.open("main", '(', null, 1, 1, 0)
        var pairChecks = 0

        val match = stack.close(
            group = "main",
            token = ')',
            context = null,
            strictContext = false,
            isPair = { left, right ->
                pairChecks++
                isPair(left, right)
            },
            isStructuralPair = ::isStructuralPair,
            canCloseStructural = false,
        )

        assertEquals(1, match?.open?.offset)
        assertEquals(1, pairChecks)
    }

    @Test
    fun `keeps matcher groups independent`() {
        val stack = BraceMatcherStack<Char, String>()
        stack.open("host", '{', null, 0, 1, 0)
        stack.open("embedded", '(', null, 1, 1, 0)

        assertEquals(0, stack.close("host", '}', null, false, ::isPair)?.open?.offset)
        assertEquals(1, stack.close("embedded", ')', null, false, ::isPair)?.open?.offset)
    }

    @Test
    fun `strict context matches only the same tag name`() {
        val stack = BraceMatcherStack<String, String>()
        val isTagPair = { left: String, right: String ->
            left == "start-tag" && right == "end-tag"
        }
        stack.open("xml", "start-tag", "section", 0, 1, 0, strictContext = true)
        stack.open("xml", "start-tag", "item", 1, 1, 0, strictContext = true)

        assertNull(
            stack.close("xml", "end-tag", "other", true, isTagPair),
        )
        assertEquals(
            "section",
            stack.close("xml", "end-tag", "section", true, isTagPair)?.open?.context,
        )
    }

    @Test(timeout = 10_000)
    fun `many unrelated closers do not rescan a deep stack`() {
        val depth = 50_000
        val stack = BraceMatcherStack<Char, String>()
        repeat(depth) { offset ->
            stack.open("main", '(', null, offset, 1, 0)
        }

        repeat(depth) {
            assertNull(stack.close("main", ']', null, false, ::isPair))
        }
        repeat(depth) { expectedDepth ->
            assertEquals(
                depth - expectedDepth - 1,
                stack.close("main", ')', null, false, ::isPair)?.open?.depth,
            )
        }
    }

    @Test(timeout = 10_000)
    fun `regular closers do not rescan beyond a structural boundary`() {
        val depth = 50_000
        val stack = BraceMatcherStack<Char, String>()
        repeat(depth) { offset ->
            stack.open("main", '(', null, offset, 1, 0)
        }
        stack.open("main", '{', null, depth, 1, 0, structural = true)

        repeat(depth) {
            assertNull(
                stack.close("main", ')', null, false, ::isPair, ::isStructuralPair),
            )
        }
        assertEquals(
            depth,
            stack.close(
                "main",
                '}',
                null,
                false,
                ::isPair,
                ::isStructuralPair,
            )?.open?.offset,
        )
        repeat(depth) {
            assertEquals(
                depth - it - 1,
                stack.close(
                    "main",
                    ')',
                    null,
                    false,
                    ::isPair,
                    ::isStructuralPair,
                )?.open?.depth,
            )
        }
    }

    @Test
    fun `checks cancellation during deep malformed recovery`() {
        val stack = BraceMatcherStack<String, String>()
        stack.open("xml", "start-tag", "root", 0, 1, 0, strictContext = true)
        repeat(10_000) { index ->
            stack.open(
                "xml",
                "start-tag",
                "dangling-$index",
                index + 1,
                1,
                0,
                strictContext = true,
            )
        }
        var cancellationChecks = 0

        try {
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "root",
                strictContext = true,
                isPair = { left, right -> left == "start-tag" && right == "end-tag" },
                checkCanceled = {
                    cancellationChecks++
                    if (cancellationChecks == 3) throw ProcessCanceledException()
                },
            )
            fail("Expected malformed recovery to be canceled")
        } catch (_: ProcessCanceledException) {
            assertEquals(3, cancellationChecks)
        }
    }

    private fun isPair(left: Char, right: Char): Boolean = when (left) {
        '(' -> right == ')'
        '[' -> right == ']'
        '{' -> right == '}'
        else -> false
    }

    private fun isStructuralPair(left: Char, right: Char): Boolean =
        left == '{' && right == '}'
}
