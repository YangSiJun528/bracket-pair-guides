package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketPair
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.concurrent.CancellationException

class GuidePositionIndexBuilderTest {
    @Test
    fun sequentialAppendPreservesMinimaAndAbsoluteAnchorLinesAcrossBlocks() {
        val builder = checkNotNull(GuidePositionIndex.builder(4, 701, {}))
        repeat(701) { line ->
            builder.append(
                when (line) {
                    100, 300, 600 -> 2
                    301 -> VisualColumn.BLANK_LINE_COLUMN
                    else -> 8
                },
            )
        }
        val index = builder.seal()

        assertThat(index.guideForOrNull(pair(3, 704))?.guideColumn).isEqualTo(2)
        assertThat(index.guideForOrNull(pair(3, 704))?.anchorLine).isEqualTo(104)
        assertThat(index.guideForOrNull(pair(259, 704))?.anchorLine).isEqualTo(304)
        assertThat(index.guideForOrNull(pair(515, 704))?.anchorLine).isEqualTo(604)
    }

    @Test
    fun sealingRequiresEveryLineAndPreventsMutationOfThePublishedIndex() {
        val builder = checkNotNull(GuidePositionIndex.builder(1, 2, {}))
        builder.append(8)
        assertThatThrownBy { builder.seal() }.isInstanceOf(IllegalStateException::class.java)
        builder.append(2)
        assertThatThrownBy { builder.append(0) }.isInstanceOf(IllegalStateException::class.java)
        val index = builder.seal()
        val bracket = pair(0, 2)
        val accepted = index.guideForOrNull(bracket)

        assertThatThrownBy { builder.append(0) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { builder.seal() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(index.guideForOrNull(bracket)).isEqualTo(accepted)
        assertThat(accepted?.guideColumn).isEqualTo(2)
        assertThat(accepted?.anchorLine).isEqualTo(2)
    }

    @Test
    fun capacityAndCoordinateRefusalsOccurBeforeBuilderCreation() {
        assertThat(GuidePositionIndex.builder(-1, 1, {})).isNull()
        assertThat(GuidePositionIndex.builder(0, 0, {})).isNull()
        assertThat(GuidePositionIndex.builder(Int.MAX_VALUE, 2, {})).isNull()
        assertThat(GuidePositionIndex.builder(0, 1_032_193, {})).isNull()
    }

    @Test
    fun cancellationStopsAppendAndCannotPublishAnUnfinishedSeal() {
        var canceled = false
        val probe = { if (canceled) throw CancellationException("canceled guide build") }
        val builder = checkNotNull(GuidePositionIndex.builder(1, 1, probe))
        canceled = true
        assertThatThrownBy { builder.append(2) }.isInstanceOf(CancellationException::class.java)
        canceled = false
        builder.append(2)
        canceled = true
        assertThatThrownBy { builder.seal() }.isInstanceOf(CancellationException::class.java)
        canceled = false
        assertThatThrownBy { builder.append(0) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { builder.seal() }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun pair(openLine: Int, closeLine: Int): BracketPair = BracketPair(
        openOffset = 0,
        openTokenLength = 1,
        closeOffset = 1,
        closeTokenLength = 1,
        depth = 0,
        openLine = openLine,
        closeLine = closeLine,
    )
}
