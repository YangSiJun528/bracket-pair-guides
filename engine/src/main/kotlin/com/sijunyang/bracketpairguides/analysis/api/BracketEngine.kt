package com.sijunyang.bracketpairguides.analysis.api

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.LinkedHashSet

/** The single typed entry point from the plugin layer into bracket analysis. */
@ApiStatus.Internal
public interface BracketEngine {
    /** Performs the requested analysis synchronously in the caller's read action. */
    public fun analyze(
        request: AnalyzeRequest,
        progress: ProgressIndicator,
    ): AnalysisResult

    /** Performs the bounded active-pair fast path synchronously. */
    public fun resolveActivePair(request: ActivePairRequest): ActivePairResult

    /** Returns installed language families backed by the official matcher API. */
    public fun installedLanguages(): List<BraceLanguageFamily>
}

/** Immutable description of the work required from a background analysis pass. */
@ApiStatus.Internal
public class AnalyzeRequest(
    public val editor: Editor,
    public val fileType: FileType,
    public val capabilities: AnalysisCapabilities,
    disabledLanguageIds: Set<String> = emptySet(),
) {
    public val disabledLanguageIds: Set<String> = immutableCopy(disabledLanguageIds)

    /** Captures the editor state against which this request must be validated. */
    public val revision: AnalysisRevision = AnalysisRevision.current(
        editor = editor,
        fileType = fileType,
        capabilities = capabilities,
        disabledLanguageIds = this.disabledLanguageIds,
    )
}

/** Immutable description of a bounded lookup on the current editor token stream. */
@ApiStatus.Internal
public class ActivePairRequest(
    public val editor: Editor,
    public val fileType: FileType,
    public val caretOffset: Int,
    disabledLanguageIds: Set<String> = emptySet(),
) {
    public val disabledLanguageIds: Set<String> = immutableCopy(disabledLanguageIds)
}

/** Result of a bounded active-pair lookup. */
@ApiStatus.Internal
public sealed interface ActivePairResult {
    /** The lookup completed, including the valid result of finding no pair. */
    public data class Complete(public val pair: BracketPair?) : ActivePairResult

    /** The transition/deadline budget was exhausted. */
    public data object Incomplete : ActivePairResult
}

/** Immutable analysis result; implementation indexes remain inside the engine. */
@ApiStatus.Internal
public interface AnalysisResult {
    public val revision: AnalysisRevision

    /** Returns the innermost pair containing [caretOffset], in O(log pairCount). */
    public fun activePairAt(caretOffset: Int): BracketPair?

    /** Returns an indexed guide, or null when that index was intentionally omitted. */
    public fun guideFor(pair: BracketPair): BracketGuide?

    /**
     * Returns a capped, allocation-light candidate view near [range].
     * Candidates start before the range end, but candidates before its start may not
     * overlap and must be clipped by their end offset. [limit] must be positive.
     * Tokens are centered around [focusOffset] when capped.
     */
    public fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): VisibleTokens
}

/** Primitive token view that does not expose the engine's global token index. */
@ApiStatus.Internal
public interface VisibleTokens {
    public val size: Int
    public val isCapped: Boolean
    public val stableFocusStartOffset: Int
    public val stableFocusEndOffset: Int

    public fun offsetAt(index: Int): Int
    public fun lengthAt(index: Int): Int
    public fun depthAt(index: Int): Int
}

private fun <T> immutableCopy(values: Set<T>): Set<T> =
    if (values.isEmpty()) emptySet() else Collections.unmodifiableSet(LinkedHashSet(values))
