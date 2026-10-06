package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily

/** Installed matcher capability listing consumed by settings without matcher implementation access. */
interface InstalledBraceLanguages {
    fun installedFamilies(): List<BraceLanguageFamily>
}
