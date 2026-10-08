package com.sijunyang.bracketpairguides.ui.work

import com.sijunyang.bracketpairguides.model.BraceLanguageFamily

/** Installed matcher capability listing consumed by settings without matcher implementation access. */
interface InstalledBraceLanguages {
    fun installedFamilies(): List<BraceLanguageFamily>
}
