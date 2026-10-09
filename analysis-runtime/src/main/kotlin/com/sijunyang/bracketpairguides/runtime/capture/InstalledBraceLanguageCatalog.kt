package com.sijunyang.bracketpairguides.runtime.capture

import com.sijunyang.bracketpairguides.model.BraceLanguageFamily
import com.sijunyang.bracketpairguides.runtime.capture.matcher.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.ui.work.InstalledBraceLanguages

/** Host adapter for the settings listing; the matching catalog remains runtime-owned. */
class InstalledBraceLanguageCatalog : InstalledBraceLanguages {
    override fun installedFamilies(): List<BraceLanguageFamily> = BraceLanguageCatalog().installedFamilies()
}
