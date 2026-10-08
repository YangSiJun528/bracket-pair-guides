package com.sijunyang.bracketpairguides.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class ResultContractTest {
    @Test fun queryRangesAreValidatedBeforeAnyImplementationReceivesThem() {
        assertThat(OffsetRange(7, 7)).isEqualTo(OffsetRange(7, 7))
        assertThatThrownBy { OffsetRange(-1, 7) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OffsetRange(8, 7) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun facetsCannotRequestGuidesWithoutActivePairQueries() {
        assertThatThrownBy { AnalysisCoverage(false, false, true) }.isInstanceOf(IllegalArgumentException::class.java)
        val full = AnalysisCoverage(true, true, true)
        assertThat(full.includes(AnalysisCoverage(true, false, false))).isTrue()
        assertThat(full.withoutGuidePosition()).isEqualTo(AnalysisCoverage(true, true, false))
    }

    @Test fun tokenRangesRejectOverflowAndOverlapWithoutIntegerWraparound() {
        val pair = BracketPair(0, 1, Int.MAX_VALUE - 1, 2, 0, 0, 1)
        assertThat(pair.hasWellFormedTokenRange(Int.MAX_VALUE)).isFalse()
        assertThat(pair.copy(closeTokenLength = 1).hasWellFormedTokenRange(Int.MAX_VALUE)).isTrue()
        assertThat(pair.copy(openTokenLength = 2, closeOffset = 1).hasWellFormedTokenRange(Int.MAX_VALUE)).isFalse()
    }

    @Test fun languageDescriptionsOwnTheirMemberNames() {
        val names = mutableListOf("Template")
        val family = BraceLanguageFamily("language", "Language", names)
        names.clear()
        assertThat(family.memberDisplayNames).containsExactly("Template")
        assertThatThrownBy { (family.memberDisplayNames as MutableList<String>).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }
}
