package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole
import org.assertj.core.api.Assertions.assertThat
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

        assertThat(topology.isPureSymmetric(symmetric)).isTrue()
    }

    @Test
    fun `mixed role token keeps opening priority instead of toggle behavior`() {
        val topology = BracePairTopology(
            arrayOf(
                BracePair(symmetric, symmetric, false),
                BracePair(symmetric, right, false),
            ),
        )

        assertThat(topology.isPureSymmetric(symmetric)).isFalse()
    }

    @Test
    fun `an extra incoming edge also prevents symmetric toggle behavior`() {
        val topology = BracePairTopology(
            arrayOf(
                BracePair(symmetric, symmetric, false),
                BracePair(left, symmetric, false),
            ),
        )

        assertThat(topology.isPureSymmetric(symmetric)).isFalse()
    }

    @Test
    fun `pure symmetric type toggles only when the matcher accepts the close role`() {
        assertThat(
            bracketRole(
                isLeft = true,
                isRight = true,
                isPureSymmetric = true,
            ),
        ).isEqualTo(BracketRole.TOGGLE)
        assertThat(
            bracketRole(
                isLeft = true,
                isRight = false,
                isPureSymmetric = true,
            ),
        ).isEqualTo(BracketRole.OPEN)
    }

    @Test
    fun `mixed role token retains opening priority`() {
        assertThat(
            bracketRole(
                isLeft = true,
                isRight = true,
                isPureSymmetric = false,
            ),
        ).isEqualTo(BracketRole.OPEN)
    }

    @Test
    fun `reports structurality for the exact registered pair`() {
        val topology = BracePairTopology(
            arrayOf(
                BracePair(left, right, false),
                BracePair(structuralLeft, structuralRight, true),
            ),
        )

        assertThat(topology.isStructuralOpen(left)).isFalse()
        assertThat(topology.isStructuralOpen(structuralLeft)).isTrue()
        assertThat(topology.isStructuralPair(left, right)).isFalse()
        assertThat(topology.isStructuralPair(structuralLeft, structuralRight)).isTrue()
        assertThat(topology.isStructuralPair(structuralLeft, right)).isFalse()
        assertThat(topology.isStructuralClose(right)).isFalse()
        assertThat(topology.isStructuralClose(structuralRight)).isTrue()
    }

    private companion object {
        val TEST_LANGUAGE = object : Language("BRACKET_PAIR_GUIDES_TOPOLOGY_TEST") {}
    }
}
