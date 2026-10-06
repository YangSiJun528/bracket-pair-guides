package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

/** Test-only synchronous composition retained for baseline and pipeline parity. */
internal class SynchronousSnapshotAssemblyReference(
    private val input: AnalysisInput,
    private val recognize: () -> DocumentBracketRecognition,
    private val checkCanceled: () -> Unit,
    private val documentLength: Int,
    private val documentLineCount: Int,
    private val guidePositions: (IntRange) -> GuidePositionIndex?,
    private val canonicalIndexes: (
        AnalysisInput,
        IndexLayout,
        PairTable,
        BracketIndexes,
    ) -> BracketIndexes,
) {
    fun outcome(): AnalysisOutcome {
        val recognition = if (input.coverage.pairs) {
            recognize()
        } else {
            DocumentBracketRecognition.Complete(
                PairTable.empty(),
                BraceMatcherAvailability.UNDETERMINED,
            )
        }
        val prepared = SnapshotCalculation.prepare(
            coverage = input.coverage,
            recognition = recognition,
            documentLength = documentLength,
            documentLineCount = documentLineCount,
            checkCanceled = checkCanceled,
        )
        return stampedOutcome(input, prepared.finish(prepared.guideLines?.let(guidePositions)), canonicalIndexes)
    }
}
