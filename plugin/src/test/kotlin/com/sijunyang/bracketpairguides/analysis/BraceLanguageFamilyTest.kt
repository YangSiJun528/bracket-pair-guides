package com.sijunyang.bracketpairguides.analysis

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class BraceLanguageFamilyTest {
    @Test
    fun testDefensivelyCopiesMemberNames() {
        val members = mutableListOf("Java")
        val family = BraceLanguageFamily("JAVA", "Java", members)

        members.clear()

        assertThat(family.memberDisplayNames).containsExactly("Java")
    }
}
