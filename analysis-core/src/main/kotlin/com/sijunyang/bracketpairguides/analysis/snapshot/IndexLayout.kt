package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage

/** Index and metadata ownership selected before token recognition starts. */
data class IndexLayout(val activePair: Boolean, val tokenStorage: TokenStorage, val guidePosition: Boolean) {
    companion object {
        fun forCoverage(coverage: AnalysisCoverage): IndexLayout = IndexLayout(
            activePair = coverage.activePair,
            tokenStorage =
            when {
                !coverage.tokens -> TokenStorage.NONE
                coverage.activePair -> TokenStorage.ATTACHED
                else -> TokenStorage.DETACHED
            },
            guidePosition = coverage.guidePosition,
        )
    }
}

enum class TokenStorage {
    NONE,
    ATTACHED,
    DETACHED,
}
