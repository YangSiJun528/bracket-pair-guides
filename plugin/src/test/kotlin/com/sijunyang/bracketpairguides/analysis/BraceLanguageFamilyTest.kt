package com.sijunyang.bracketpairguides.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class BraceLanguageFamilyTest {
    @Test
    fun testDefensivelyCopiesMemberNames() {
        val members = mutableListOf("Java")
        val family = BraceLanguageFamily("JAVA", "Java", members)

        members.clear()

        assertEquals(listOf("Java"), family.memberDisplayNames)
    }
}
