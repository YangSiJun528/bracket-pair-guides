package com.sijunyang.bracketpairguides.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class BraceLanguageFamilyTest {
    @Test
    fun testDefensivelyCopiesMemberNames() {
        val members = mutableListOf("Java")
        val family = BraceLanguageFamily("JAVA", "Java", members)

        members.clear()

        assertThat(family.memberDisplayNames).containsExactly("Java")
    }

    @Test
    fun memberNamesCannotBeMutatedThroughThePublishedView() {
        val family = BraceLanguageFamily("JAVA", "Java", listOf("Java"))
        assertThatThrownBy { (family.memberDisplayNames as MutableList<String>).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(family.memberDisplayNames).containsExactly("Java")
    }
}
