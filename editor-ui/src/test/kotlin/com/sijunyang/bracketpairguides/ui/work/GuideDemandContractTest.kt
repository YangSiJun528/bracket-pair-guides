package com.sijunyang.bracketpairguides.ui.work

import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class GuideDemandContractTest {
    @Test fun `construction and copy freeze caller language selections`() {
        val languages = mutableSetOf("JAVA")
        val original = GuideDemand(
            0,
            AnalysisCoverage(true, true, true),
            languages,
            true,
            0,
            null,
            GuideChange.CONFIGURATION,
            NativeInterest(0, false),
        )
        languages += "XML"
        assertThat(original.disabledLanguageIds).containsExactly("JAVA")
        val copied = original.copy(disabledLanguageIds = languages)
        languages.clear()
        assertThat(copied.disabledLanguageIds).containsExactlyInAnyOrder("JAVA", "XML")
        assertThat(original.copy()).isEqualTo(original)
    }
}
