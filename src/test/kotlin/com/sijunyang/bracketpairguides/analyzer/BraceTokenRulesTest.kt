package com.sijunyang.bracketpairguides.analyzer

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BraceTokenRulesTest {
    private val language = TEST_LANGUAGE
    private val left = IElementType("LEFT", language)
    private val alternativeLeft = IElementType("ALTERNATIVE_LEFT", language)
    private val right = IElementType("RIGHT", language)
    private val alternativeRight = IElementType("ALTERNATIVE_RIGHT", language)
    private val symmetric = IElementType("SYMMETRIC", language)

    @Test
    fun `preserves every closing alternative for one opening token`() {
        val rules = BraceTokenRules(
            arrayOf(
                BracePair(left, right, false),
                BracePair(left, alternativeRight, false),
            ),
        )

        assertEquals(setOf(right, alternativeRight), rules.expectedCloses(left))
        assertTrue(rules.isClose(right))
        assertTrue(rules.isClose(alternativeRight))
    }

    @Test
    fun `recognizes a pure symmetric delimiter`() {
        val rules = BraceTokenRules(arrayOf(BracePair(symmetric, symmetric, false)))

        assertTrue(rules.isPureSymmetric(symmetric))
    }

    @Test
    fun `mixed role token keeps opening priority instead of toggle behavior`() {
        val rules = BraceTokenRules(
            arrayOf(
                BracePair(symmetric, symmetric, false),
                BracePair(symmetric, right, false),
            ),
        )

        assertFalse(rules.isPureSymmetric(symmetric))
        assertEquals(setOf(symmetric, right), rules.expectedCloses(symmetric))
    }

    @Test
    fun `shared closing token is retained for different opening tokens`() {
        val rules = BraceTokenRules(
            arrayOf(
                BracePair(left, right, false),
                BracePair(alternativeLeft, right, false),
            ),
        )

        assertEquals(setOf(right), rules.expectedCloses(left))
        assertEquals(setOf(right), rules.expectedCloses(alternativeLeft))
        assertTrue(rules.isClose(right))
    }

    private companion object {
        val TEST_LANGUAGE = object : Language("BRACKET_PAIR_GUIDES_TEST") {}
    }
}
