package com.sijunyang.bracketpairguides.analysis

import com.sijunyang.bracketpairguides.analysis.intellij.DocumentGuidePositions
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.SnapshotAssembly

/** Builds a test result through the production snapshot assembly and indexes. */
internal fun AnalysisInput.bracketSnapshot(
    pairs: Iterable<BracketPair>,
    matcherAvailability: BraceMatcherAvailability = BraceMatcherAvailability.AVAILABLE,
): BracketSnapshot {
    val document = editor.document
    val guidePositions = DocumentGuidePositions(
        document = document,
        tabSize = stamp.tabSize,
        checkCanceled = {},
    )
    val outcome = SnapshotAssembly(
        input = this,
        recognize = {
            DocumentBracketRecognition.Complete(
                pairs.toPairTable(),
                matcherAvailability,
            )
        },
        checkCanceled = {},
        documentLength = document.textLength,
        documentLineCount = document.lineCount,
        guidePositions = guidePositions::index,
        canonicalIndexes = { _, _, _, indexes -> indexes },
    ).outcome()
    return (outcome as? AnalysisOutcome.Complete)?.snapshot
        ?: error("Expected complete fixture analysis, got ${outcome::class.java.simpleName}")
}
