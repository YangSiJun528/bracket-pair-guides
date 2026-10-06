package com.sijunyang.bracketpairguides.analysis.snapshot

/** Product boundary that prevented an authoritative snapshot. */
enum class AnalysisLimit {
    IDE_CODE_INSIGHT_FILE_SIZE,
    PAIR_CAPACITY,
    PENDING_OPEN_CAPACITY,
    GUIDE_CAPACITY,
}
