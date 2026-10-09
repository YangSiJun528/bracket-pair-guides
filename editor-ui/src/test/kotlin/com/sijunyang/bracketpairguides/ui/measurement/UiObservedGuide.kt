package com.sijunyang.bracketpairguides.ui.measurement

data class UiObservedGuide(
    val openOffset: Int,
    val openTokenLength: Int,
    val closeOffset: Int,
    val closeTokenLength: Int,
    val depth: Int,
    val openLine: Int,
    val closeLine: Int,
    val guideColumn: Int,
    val anchorLine: Int,
)
