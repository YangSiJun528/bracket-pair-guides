package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.active.CaretBracketSearch
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBrackets
import com.sijunyang.bracketpairguides.analysis.pipeline.SnapshotAssembly
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.LinkedHashSet

/** Application entry point for bracket analysis. */
@ApiStatus.Internal
public class BracketAnalysis {
    private val languages = BraceLanguageCatalog()

    /** Performs the requested analysis synchronously in the caller's read action. */
    public fun analyze(
        input: AnalysisInput,
        progress: ProgressIndicator,
    ): BracketSnapshot {
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
        ).snapshot()
    }

    /** Performs the bounded active-pair fast path synchronously. */
    public fun resolveActivePair(context: CaretContext): ActivePairKnowledge {
        val disabledLanguageIds = context.disabledLanguageIds
        return CaretBracketSearch(
            fileType = context.fileType,
            languages = languages,
            isLanguageEnabled = { capabilityId -> capabilityId !in disabledLanguageIds },
        ).findInnermost(context.editor, context.caretOffset)
    }

    /** Returns installed language families backed by the official matcher API. */
    public fun installedLanguages(): List<BraceLanguageFamily> =
        languages.installedFamilies()
}

/** Immutable input captured for one background analysis pass. */
@ApiStatus.Internal
public class AnalysisInput(
    public val editor: Editor,
    public val fileType: FileType,
    public val coverage: AnalysisCoverage,
    disabledLanguageIds: Set<String> = emptySet(),
) {
    public val disabledLanguageIds: Set<String> = immutableCopy(disabledLanguageIds)

    public val stamp: AnalysisStamp = AnalysisStamp(
        editor = editor,
        fileType = fileType,
        coverage = coverage,
        disabledLanguageIds = this.disabledLanguageIds,
    )
}

/** Immutable editor and caret context for a bounded active-pair lookup. */
@ApiStatus.Internal
public class CaretContext(
    public val editor: Editor,
    public val fileType: FileType,
    public val caretOffset: Int,
    disabledLanguageIds: Set<String> = emptySet(),
) {
    public val disabledLanguageIds: Set<String> = immutableCopy(disabledLanguageIds)
}

/** Knowledge produced by a bounded active-pair lookup. */
@ApiStatus.Internal
public sealed interface ActivePairKnowledge {
    /** The lookup completed, including the valid knowledge that no pair exists. */
    public data class Known(public val pair: BracketPair?) : ActivePairKnowledge

    /** The transition or deadline allowance was exhausted. */
    public data object Unknown : ActivePairKnowledge
}

private fun <T> immutableCopy(values: Set<T>): Set<T> =
    if (values.isEmpty()) emptySet() else Collections.unmodifiableSet(LinkedHashSet(values))
