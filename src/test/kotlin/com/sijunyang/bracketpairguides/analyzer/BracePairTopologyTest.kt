package com.sijunyang.bracketpairguides.analyzer

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BracePairTopologyTest {
    private val language = TEST_LANGUAGE
    private val right = IElementType("RIGHT", language)
    private val symmetric = IElementType("SYMMETRIC", language)

    @Test
    fun `recognizes a pure symmetric delimiter`() {
        val topology = BracePairTopology(arrayOf(BracePair(symmetric, symmetric, false)))

        assertTrue(topology.isPureSymmetric(symmetric))
    }

    @Test
    fun `mixed role token keeps opening priority instead of toggle behavior`() {
        val topology = BracePairTopology(
            arrayOf(
                BracePair(symmetric, symmetric, false),
                BracePair(symmetric, right, false),
            ),
        )

        assertFalse(topology.isPureSymmetric(symmetric))
    }

    private companion object {
        val TEST_LANGUAGE = object : Language("BRACKET_PAIR_GUIDES_TOPOLOGY_TEST") {}
    }
}
