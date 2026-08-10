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
    var acceptedStamp: AnalysisStamp? = null

    var snapshot: BracketSnapshot? = null

    fun clear() {
        acceptedStamp = null
        snapshot = null
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

    fun covers(required: AnalysisStamp): Boolean = acceptedStamp?.covers(required) == true

    private companion object {
        private val TOKEN_COVERAGE = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
    }
}
