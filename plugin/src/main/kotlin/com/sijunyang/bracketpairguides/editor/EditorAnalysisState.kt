package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BracketSnapshot

/** Analysis snapshot and acceptance state owned by one editor session. */
internal class EditorAnalysisState(
    private val editor: Editor,
) {
    @Volatile
    private var acceptedStamp: AnalysisStamp? = null

    @Volatile
    private var refusal: AnalysisRefusal? = null

    var snapshot: BracketSnapshot? = null

    fun clear() {
        forgetAcceptance()
        snapshot = null
    }

    fun accept(stamp: AnalysisStamp) {
        acceptedStamp = stamp
        if (refusal?.stamp?.let(stamp::covers) == true) {
            refusal = null
        }
    }

    fun acceptLimited(
        completedStamp: AnalysisStamp,
        attemptedStamp: AnalysisStamp,
    ) {
        acceptedStamp = completedStamp
        refusal = AnalysisRefusal(
            stamp = attemptedStamp,
            limit = AnalysisLimit.GUIDE_CAPACITY,
            completedStamp = completedStamp,
        )
    }

    fun refuse(stamp: AnalysisStamp, limit: AnalysisLimit) {
        acceptedStamp = null
        refusal = AnalysisRefusal(stamp, limit)
    }

    fun forgetCompletion() {
        acceptedStamp = null
    }

    fun forgetAcceptance() {
        acceptedStamp = null
        refusal = null
    }

    fun discardStale(
        fileType: FileType,
        requiredCoverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
    ) {
        if (snapshot?.stamp?.matchesCurrent(
                editor,
                fileType,
                requiredCoverage,
                disabledLanguageIds,
            ) == false
        ) {
            snapshot = null
        }
        if (acceptedStamp?.matchesCurrent(
                editor,
                fileType,
                requiredCoverage,
                disabledLanguageIds,
            ) == false
        ) {
            acceptedStamp = null
        }
        if (refusal?.stamp?.let { stamp ->
                !stamp.matchesCurrent(
                    editor,
                    fileType,
                    stamp.coverage,
                    disabledLanguageIds,
                )
            } == true
        ) {
            refusal = null
        }
    }

    fun currentStamp(
        fileType: FileType,
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
    ): AnalysisStamp = AnalysisInput(
        editor = editor,
        fileType = fileType,
        coverage = coverage,
        disabledLanguageIds = disabledLanguageIds,
    ).stamp

    fun hasCurrentActivePair(
        candidate: BracketSnapshot,
        fileType: FileType,
        disabledLanguageIds: Set<String>,
    ): Boolean = candidate.stamp.matchesCurrent(
        editor,
        fileType,
        ACTIVE_PAIR_COVERAGE,
        disabledLanguageIds,
    )

    fun hasCurrentTokens(
        candidate: BracketSnapshot,
        fileType: FileType,
        disabledLanguageIds: Set<String>,
    ): Boolean = candidate.stamp.matchesCurrent(
        editor,
        fileType,
        TOKEN_COVERAGE,
        disabledLanguageIds,
    )

    fun shouldReleasePairGraph(
        required: AnalysisCoverage,
        provided: AnalysisCoverage,
    ): Boolean = required.tokens &&
        !required.activePair &&
        provided.activePair

    fun covers(
        required: AnalysisStamp,
        includeIdeSizeRefusal: Boolean = true,
    ): Boolean {
        val completed = acceptedStamp
        if (completed?.covers(required) == true) return true
        val rejected = refusal ?: return false
        if (rejected.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE &&
            !includeIdeSizeRefusal
        ) {
            return false
        }
        if (rejected.stamp.coverage != required.coverage ||
            !rejected.stamp.covers(required)
        ) {
            return false
        }
        val requiredCompletion = rejected.completedStamp ?: return true
        return completed?.covers(requiredCompletion) == true
    }

    fun hasCompleted(required: AnalysisStamp): Boolean =
        acceptedStamp?.covers(required) == true

    fun hasRefused(required: AnalysisStamp, limit: AnalysisLimit): Boolean =
        refusal?.let { rejected ->
            rejected.limit == limit &&
                rejected.stamp.coverage == required.coverage &&
                rejected.stamp.covers(required)
        } == true

    private companion object {
        private val ACTIVE_PAIR_COVERAGE = AnalysisCoverage(
            tokens = false,
            activePair = true,
            guidePosition = false,
        )
        private val TOKEN_COVERAGE = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
    }

    private data class AnalysisRefusal(
        val stamp: AnalysisStamp,
        val limit: AnalysisLimit,
        val completedStamp: AnalysisStamp? = null,
    )
}
