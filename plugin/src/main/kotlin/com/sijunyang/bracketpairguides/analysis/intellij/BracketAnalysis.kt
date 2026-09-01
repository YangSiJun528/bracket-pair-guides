package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBrackets
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.DocumentBracketIndexes
import com.sijunyang.bracketpairguides.analysis.snapshot.SnapshotAssembly

/** IntelliJ composition of token recognition and immutable snapshot policy. */
@Service(Service.Level.APP)
internal class BracketAnalysis {
    private val languages = BraceLanguageCatalog()
    private val documentIndexes = DocumentBracketIndexes()

    fun analyze(input: AnalysisInput, progress: ProgressIndicator): AnalysisOutcome {
        val disabledLanguageIds = input.disabledLanguageIds
        val documentBrackets =
            DocumentBrackets(
                editor = input.editor,
                fileType = input.fileType,
                languages = languages,
            ) { capabilityId ->
                capabilityId !in disabledLanguageIds
            }
        val document = input.editor.document
        val guidePositions =
            DocumentGuidePositions(
                document = document,
                tabSize = input.stamp.tabSize,
                checkCanceled = progress::checkCanceled,
            )
        return SnapshotAssembly(
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
