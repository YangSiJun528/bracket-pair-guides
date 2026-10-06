package com.sijunyang.bracketpairguides.analysis

import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionTestAdapter
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.SnapshotCalculation
import com.sijunyang.bracketpairguides.analysis.snapshot.stampedOutcome

/** Builds supplied fixture pairs through production pure calculation and stamped publication. */
internal fun AnalysisInput.bracketSnapshot(
    pairs: Iterable<BracketPair>,
    matcherAvailability: BraceMatcherAvailability = BraceMatcherAvailability.AVAILABLE,
): BracketSnapshot {
    val document = editor.document
    val guidePositions =
        GuidePositionTestAdapter(
            document = document,
            tabSize = stamp.tabSize,
            checkCanceled = {},
        )
    val prepared = SnapshotCalculation.prepare(
        coverage = coverage,
        recognition = DocumentBracketRecognition.Complete(pairs.toPairTable(), matcherAvailability),
        checkCanceled = {},
        documentLength = document.textLength,
        documentLineCount = document.lineCount,
    )
    val outcome = stampedOutcome(
        this,
        prepared.finish(prepared.guideLines?.let(guidePositions::index)),
    ) { _, _, _, indexes -> indexes }
    return (outcome as? AnalysisOutcome.Complete)?.snapshot
        ?: error("Expected complete fixture analysis, got ${outcome::class.java.simpleName}")
}
