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
        val snapshot = BracketSnapshot(
            stamp = snapshotInput.stamp,
            matcherAvailability = calculated.matcherAvailability,
            indexes = canonicalIndexes(
                snapshotInput,
                calculated.layout,
                calculated.canonicalPairs,
                calculated.indexes,
            ),
        )
        if (calculated.limit != null) {
            AnalysisOutcome.Limited(input.stamp, snapshot, calculated.limit)
        } else {
            AnalysisOutcome.Complete(snapshot)
        }
    }
}
