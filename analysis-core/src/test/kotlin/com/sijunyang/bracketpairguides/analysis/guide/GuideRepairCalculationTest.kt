package com.sijunyang.bracketpairguides.analysis.guide

import com.sijunyang.bracketpairguides.analysis.BracketPair
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.concurrent.CancellationException

class GuideRepairCalculationTest {
    @Test
    fun exactRepairCountsTheFirstContentCharacterAndRefusesBeyondTheSharedCharacterCap() {
        val admitted = listOf(" ".repeat(32_758) + "x", "        }")
        assertThat(calculate(admitted)?.guideColumn).isEqualTo(8)
        assertThat(calculate(listOf(" ".repeat(32_759) + "x", "        }"))).isNull()
    }

    @Test
    fun exactRepairAdmits256LinesAndRefuses257WithoutPartialGeometry() {
        assertThat(calculate(List(256) { "    x" })?.anchorLine).isEqualTo(1)
        assertThat(calculate(List(257) { "    x" })).isNull()
    }

    @Test
    fun zeroMinimumStopsBeforeLongLaterIndentationAndTiesChooseTheEarliestLine() {
        val lines = listOf(" 	x", "	 x", "x", "x", " ".repeat(40_000) + "x")
        for (chunkSize in listOf(1, 7, 4_096)) {
            val guide = checkNotNull(calculate(lines, chunkSize))
            assertThat(guide.guideColumn).isZero()
            assertThat(guide.anchorLine).isEqualTo(3)
        }
    }

    @Test
    fun blankLinesConsumeWhitespaceAndResolveToTheFirstCandidateWhenFullyScanned() {
        val guide = checkNotNull(calculate(listOf("", " \t", "    ")))
        assertThat(guide.guideColumn).isZero()
        assertThat(guide.anchorLine).isEqualTo(1)
        assertThat(calculate(listOf(" ".repeat(32_769)))).isNull()
    }

    @Test
    fun provisionalStrategyKeepsClosingIndentOnBudgetExhaustion() {
        val lines = listOf(" ".repeat(40_000) + "x", "zero", "    }")
        val guide = checkNotNull(calculate(lines, exact = false))
        assertThat(guide.guideColumn).isEqualTo(4)
        assertThat(guide.anchorLine).isEqualTo(3)
    }

    @Test
    fun canceledCalculationCannotProduceAPublicationPayload() {
        var canceled = false
        val calculation = GuideRepairCalculation(pair(2), 1..2, 4, true, null) {
            if (canceled) throw CancellationException()
        }
        assertThat(calculation.nextLine()).isEqualTo(1)
        canceled = true
        assertThatThrownBy { calculation.append(" x", true) }.isInstanceOf(CancellationException::class.java)
    }

    private fun calculate(lines: List<String>, chunkSize: Int = 4_096, exact: Boolean = true) =
        GuideRepairCalculation(pair(lines.size), 1..lines.size, 4, exact, null, {}).let { calculation ->
            while (true) {
                val line = calculation.nextLine() ?: break
                val text = lines[line - 1]
                var offset = 0
                do {
                    val next = minOf(offset + chunkSize, text.length)
                    val resolved = calculation.append(text.substring(offset, next), next == text.length)
                    offset = next
                } while (!resolved)
            }
            calculation.result()
        }

    private fun pair(lastLine: Int) = BracketPair(0, 1, 100, 1, 0, 0, lastLine)
}
