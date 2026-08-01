package com.sijunyang.bracketpairguides.analyzer

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ContextualBracketStackTest {
    @Test
    fun `strict context matches an XML tag name`() {
        val stack = ContextualBracketStack<String, String>()

        stack.open("xml", "start-tag", "section", 0, 1, 0)
        stack.open("xml", "start-tag", "item", 1, 1, 0)

        assertEquals(
            1,
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "item",
                strictContext = true,
                isPair = ::isTagPair,
            )?.open?.depth,
        )
        assertEquals(
            "section",
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "section",
                strictContext = true,
                isPair = ::isTagPair,
            )?.open?.context,
        )
    }

    @Test
    fun `unrelated strict context does not discard an opener`() {
        val stack = ContextualBracketStack<String, String>()
        stack.open("xml", "start-tag", "section", 0, 1, 0)

        assertNull(
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "other",
                strictContext = true,
                isPair = ::isTagPair,
            ),
        )
        assertEquals(
            "section",
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "section",
                strictContext = true,
                isPair = ::isTagPair,
            )?.open?.context,
        )
    }

    @Test
    fun `matching outer context recovers past malformed inner tags`() {
        val stack = ContextualBracketStack<String, String>()
        stack.open("xml", "start-tag", "section", 0, 1, 0)
        stack.open("xml", "start-tag", "unclosed", 1, 1, 0)

        assertEquals(
            "section",
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "section",
                strictContext = true,
                isPair = ::isTagPair,
            )?.open?.context,
        )
        assertNull(
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "unclosed",
                strictContext = true,
                isPair = ::isTagPair,
            ),
        )
    }

    @Test
    fun `non-strict matcher ignores context`() {
        val stack = ContextualBracketStack<String, String>()
        stack.open("markdown", "left", "first", 0, 1, 0)

        assertEquals(
            "first",
            stack.close(
                group = "markdown",
                token = "right",
                context = "second",
                strictContext = false,
                isPair = { left, right -> left == "left" && right == "right" },
            )?.open?.context,
        )
    }

    @Test
    fun `checks cancellation during deep malformed recovery`() {
        val stack = ContextualBracketStack<String, String>()
        stack.open("xml", "start-tag", "root", 0, 1, 0)
        repeat(10_000) { index ->
            stack.open("xml", "start-tag", "dangling-$index", index + 1, 1, 0)
        }
        var cancellationChecks = 0

        try {
            stack.close(
                group = "xml",
                token = "end-tag",
                context = "root",
                strictContext = true,
                isPair = ::isTagPair,
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

    private fun isTagPair(left: String, right: String): Boolean {
        return left == "start-tag" && right == "end-tag"
    }
}
