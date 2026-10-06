package com.sijunyang.bracketpairguides.analysis.intellij

import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.settings.InstalledBraceLanguages

/** Host adapter for the settings listing; the matching catalog remains runtime-owned. */
class InstalledBraceLanguageCatalog : InstalledBraceLanguages {
    override fun installedFamilies(): List<BraceLanguageFamily> = BraceLanguageCatalog().installedFamilies()
}
