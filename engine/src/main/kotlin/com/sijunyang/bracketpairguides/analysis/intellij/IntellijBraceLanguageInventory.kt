package com.sijunyang.bracketpairguides.analysis.intellij

import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.BraceLanguageInventory
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog

/** IntelliJ view of installed brace-matcher language families. */
internal class IntellijBraceLanguageInventory : BraceLanguageInventory {
    private val languages = BraceLanguageCatalog()

    override fun families(): List<BraceLanguageFamily> =
        languages.installedFamilies()
}
