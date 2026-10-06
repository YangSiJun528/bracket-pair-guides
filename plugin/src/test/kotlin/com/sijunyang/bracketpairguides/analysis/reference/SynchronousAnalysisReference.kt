package com.sijunyang.bracketpairguides.analysis.reference

import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.intellij.SynchronousGuidePositionReference
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.pairing.TokenGrammarTestAdapter
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.DocumentBracketIndexes
import com.sijunyang.bracketpairguides.analysis.snapshot.SynchronousSnapshotAssemblyReference

/**
 * Explicit test-only synchronous control flow, extracted before production-path removal.
 * Matcher/classifier/pairing/calculation/canonical-index dependencies remain shared with production.
 * This is not a fully frozen historic baseline binary; saved M0/compact raw artifacts are that evidence.
 * Never use this reference in editor integration tests.
 */
internal class SynchronousAnalysisReference {
    private val languages = BraceLanguageCatalog()
    private val documentIndexes = DocumentBracketIndexes()

    fun analyze(input: AnalysisInput, progress: ProgressIndicator): AnalysisOutcome {
        val disabledLanguageIds = input.disabledLanguageIds
        val documentBrackets =
            TokenGrammarTestAdapter(
                editor = input.editor,
                fileType = input.fileType,
                languages = languages,
            ) { capabilityId ->
                capabilityId !in disabledLanguageIds
            }
        val document = input.editor.document
        val guidePositions =
            SynchronousGuidePositionReference(
                document = document,
                tabSize = input.stamp.tabSize,
                checkCanceled = progress::checkCanceled,
            )
        return SynchronousSnapshotAssemblyReference(
            input = input,
            recognize = { documentBrackets.recognize(progress) },
            checkCanceled = progress::checkCanceled,
            documentLength = document.textLength,
            documentLineCount = document.lineCount,
            guidePositions = guidePositions::index,
            canonicalIndexes = { snapshotInput, layout, pairs, indexes ->
                documentIndexes.canonical(
                    input = snapshotInput,
                    layout = layout,
                    pairs = pairs,
                    candidate = indexes,
                    checkCanceled = progress::checkCanceled,
                )
            },
        ).outcome()
    }
}
