package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBrackets
import com.sijunyang.bracketpairguides.analysis.pipeline.SnapshotAssembly
import org.jetbrains.annotations.ApiStatus

/** Application entry point for bracket analysis. */
@ApiStatus.Internal
public class BracketAnalysis {
    private val languages = BraceLanguageCatalog()
    private val documentIndexes = DocumentBracketIndexes()

    /** Performs the requested analysis synchronously in the caller's read action. */
    public fun analyze(
        input: AnalysisInput,
        progress: ProgressIndicator,
    ): AnalysisOutcome {
        val disabledLanguageIds = input.disabledLanguageIds
        val documentBrackets = DocumentBrackets(
            editor = input.editor,
            fileType = input.fileType,
            languages = languages,
        ) { capabilityId ->
            capabilityId !in disabledLanguageIds
        }
        return SnapshotAssembly(
            input = input,
            documentBrackets = documentBrackets,
            progress = progress,
            canonicalIndexes = { layout, pairs, indexes ->
                documentIndexes.canonical(
                    input = input,
                    layout = layout,
                    pairs = pairs,
                    candidate = indexes,
                    checkCanceled = progress::checkCanceled,
                )
            },
        ).snapshot()
    }

    /** Returns installed language families backed by the official matcher API. */
    public fun installedLanguages(): List<BraceLanguageFamily> =
        languages.installedFamilies()
}
