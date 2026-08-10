package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BracketSnapshot

/** Analysis snapshot and acceptance state owned by one editor session. */
internal class EditorAnalysisState(
    private val editor: Editor,
) {
    @Volatile
    private var acceptedStamp: AnalysisStamp? = null

    @Volatile
    private var unavailableStamp: AnalysisStamp? = null

    var snapshot: BracketSnapshot? = null

    fun clear() {
        forgetAcceptance()
        snapshot = null
    }

    fun accept(stamp: AnalysisStamp) {
        acceptedStamp = stamp
        unavailableStamp = null
    }

    fun refuse(stamp: AnalysisStamp) {
        acceptedStamp = null
        unavailableStamp = stamp
    }

    fun forgetAcceptance() {
        acceptedStamp = null
        unavailableStamp = null
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
        if (unavailableStamp?.let { stamp ->
                stamp.coverage != requiredCoverage ||
                    !stamp.matchesCurrent(
                        editor,
                        fileType,
                        requiredCoverage,
                        disabledLanguageIds,
                    )
            } == true
        ) {
            unavailableStamp = null
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

    fun isCurrent(
        candidate: BracketSnapshot,
        fileType: FileType,
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
    ): Boolean = candidate.stamp.matchesCurrent(
        editor,
        fileType,
        coverage,
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

    fun covers(required: AnalysisStamp): Boolean =
        acceptedStamp?.covers(required) == true ||
            unavailableStamp?.let { refused ->
                refused.coverage == required.coverage && refused.covers(required)
            } == true

    fun hasCompleted(required: AnalysisStamp): Boolean =
        acceptedStamp?.covers(required) == true

    private companion object {
        private val TOKEN_COVERAGE = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
    }
}
