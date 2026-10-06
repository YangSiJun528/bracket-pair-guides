package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

/** Applies the captured publication identity only after platform-free calculation has completed. */
internal fun stampedOutcome(
    input: AnalysisInput,
    calculated: CalculatedAnalysis,
    canonicalIndexes: (AnalysisInput, IndexLayout, PairTable, BracketIndexes) -> BracketIndexes,
): AnalysisOutcome = when (calculated) {
    is CalculatedAnalysis.Unavailable -> AnalysisOutcome.Unavailable(input.stamp, calculated.limit)

    is CalculatedAnalysis.Available -> {
        val snapshotInput = input.withCoverage(calculated.coverage)
        val snapshot = canonicalIndexes(
            snapshotInput,
            calculated.layout,
            calculated.canonicalPairs,
            calculated.indexes,
        ).newSnapshot(snapshotInput.stamp, calculated.matcherAvailability)
        val limit = calculated.limit
        if (limit != null) {
            AnalysisOutcome.Limited(input.stamp, snapshot, limit)
        } else {
            AnalysisOutcome.Complete(snapshot)
        }
    }
}
