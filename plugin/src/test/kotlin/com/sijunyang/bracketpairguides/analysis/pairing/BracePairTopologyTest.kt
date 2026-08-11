package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BracePairTopologyTest {
    private val language = TEST_LANGUAGE
    private val left = IElementType("LEFT", language)
    private val right = IElementType("RIGHT", language)
    private val structuralLeft = IElementType("STRUCTURAL_LEFT", language)
    private val structuralRight = IElementType("STRUCTURAL_RIGHT", language)
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

    @Test
    fun `an extra incoming edge also prevents symmetric toggle behavior`() {
        val topology = BracePairTopology(
            arrayOf(
                BracePair(symmetric, symmetric, false),
                BracePair(left, symmetric, false),
            ),
        )

        assertFalse(topology.isPureSymmetric(symmetric))
    }

    @Test
    fun `pure symmetric type toggles only when the matcher accepts the close role`() {
        assertEquals(
            BracketRole.TOGGLE,
            bracketRole(
                isLeft = true,
                isRight = true,
                isPureSymmetric = true,
            ),
        )
        assertEquals(
            BracketRole.OPEN,
            bracketRole(
                isLeft = true,
                isRight = false,
                isPureSymmetric = true,
            ),
        )
    }

    @Test
    fun `mixed role token retains opening priority`() {
        assertEquals(
            BracketRole.OPEN,
            bracketRole(
                isLeft = true,
                isRight = true,
                isPureSymmetric = false,
            ),
        )
    }

    @Test
    fun `reports structurality for the exact registered pair`() {
        val topology = BracePairTopology(
            arrayOf(
                BracePair(left, right, false),
                BracePair(structuralLeft, structuralRight, true),
            ),
        )

        assertFalse(topology.isStructuralOpen(left))
        assertTrue(topology.isStructuralOpen(structuralLeft))
        assertFalse(topology.isStructuralPair(left, right))
        assertTrue(topology.isStructuralPair(structuralLeft, structuralRight))
        assertFalse(topology.isStructuralPair(structuralLeft, right))
        assertFalse(topology.isStructuralClose(right))
        assertTrue(topology.isStructuralClose(structuralRight))
    }

    private companion object {
        val TEST_LANGUAGE = object : Language("BRACKET_PAIR_GUIDES_TOPOLOGY_TEST") {}
    }
}
