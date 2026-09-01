package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot

/** Atomic analysis acceptance owned by one editor session. */
internal class EditorAnalysisState(private val editor: Editor) {
    /** Background passes must observe the snapshot and its acceptance as one value. */
    @Volatile
    private var acceptance = AnalysisAcceptance.EMPTY

    val snapshot: BracketSnapshot?
        get() = acceptance.snapshot

    fun clear() {
        acceptance = AnalysisAcceptance.EMPTY
    }

    fun publishComplete(snapshot: BracketSnapshot) {
        acceptance = acceptance.complete(snapshot)
    }

    fun publishComplete(stamp: AnalysisStamp) {
        acceptance = AnalysisAcceptance(completedStamp = stamp)
    }

    fun publishPending(snapshot: BracketSnapshot) {
        acceptance =
            acceptance.copy(
                snapshot = snapshot,
                completedStamp = null,
            )
    }

    fun publishLimited(snapshot: BracketSnapshot, attemptedStamp: AnalysisStamp) {
        val completedStamp = snapshot.stamp
        acceptance =
            AnalysisAcceptance(
                snapshot = snapshot,
                completedStamp = completedStamp,
                refusal =
                AnalysisRefusal(
                    stamp = attemptedStamp,
                    limit = AnalysisLimit.GUIDE_CAPACITY,
                    completedStamp = completedStamp,
                ),
            )
    }

    fun publishUnavailable(stamp: AnalysisStamp, limit: AnalysisLimit) {
        acceptance =
            AnalysisAcceptance(
                refusal = AnalysisRefusal(stamp, limit),
            )
    }

    fun forgetCompletion() {
        acceptance = acceptance.copy(completedStamp = null)
    }

    fun forgetAcceptance() {
        acceptance =
            acceptance.copy(
                completedStamp = null,
                refusal = null,
            )
    }

    fun discardStale(fileType: FileType, requiredCoverage: AnalysisCoverage, disabledLanguageIds: Set<String>) {
        val current = acceptance
        val currentSnapshot =
            current.snapshot?.takeIf { snapshot ->
                snapshot.stamp.matchesCurrent(
                    editor,
                    fileType,
                    requiredCoverage,
                    disabledLanguageIds,
                )
            }
        val completed =
            current.completedStamp?.takeIf { stamp ->
                stamp.matchesCurrent(
                    editor,
                    fileType,
                    requiredCoverage,
                    disabledLanguageIds,
                )
            }
        val refusal =
            current.refusal?.takeIf { refusal ->
                refusal.stamp.matchesCurrent(
                    editor,
                    fileType,
                    refusal.stamp.coverage,
                    disabledLanguageIds,
                )
            }
        if (currentSnapshot !== current.snapshot ||
            completed !== current.completedStamp ||
            refusal !== current.refusal
        ) {
            acceptance = AnalysisAcceptance(currentSnapshot, completed, refusal)
        }
    }

    fun currentStamp(fileType: FileType, coverage: AnalysisCoverage, disabledLanguageIds: Set<String>): AnalysisStamp =
        AnalysisInput(
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

    fun hasCurrentTokens(candidate: BracketSnapshot, fileType: FileType, disabledLanguageIds: Set<String>): Boolean =
        candidate.stamp.matchesCurrent(
            editor,
            fileType,
            TOKEN_COVERAGE,
            disabledLanguageIds,
        )

    fun shouldReleasePairGraph(required: AnalysisCoverage, provided: AnalysisCoverage): Boolean = required.tokens &&
        !required.activePair &&
        provided.activePair

    /** Whether a background pass can omit the same analysis request. */
    fun canSkip(required: AnalysisStamp): Boolean {
        val current = acceptance
        if (current.completedStamp?.covers(required) == true) return true
        val refusal = current.refusal ?: return false
        // File byte length can shrink without changing the document stamp.
        if (refusal.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE) return false
        if (refusal.stamp.coverage != required.coverage ||
            !refusal.stamp.covers(required)
        ) {
            return false
        }
        val requiredCompletion = refusal.completedStamp ?: return true
        return current.completedStamp?.covers(requiredCompletion) == true
    }

    fun hasCompleted(required: AnalysisStamp): Boolean = acceptance.completedStamp?.covers(required) == true

    fun hasRefused(required: AnalysisStamp, limit: AnalysisLimit): Boolean = acceptance.refusal?.let { refusal ->
        refusal.limit == limit &&
            refusal.stamp.coverage == required.coverage &&
            refusal.stamp.covers(required)
    } == true

    private data class AnalysisAcceptance(
        val snapshot: BracketSnapshot? = null,
        val completedStamp: AnalysisStamp? = null,
        val refusal: AnalysisRefusal? = null,
    ) {
        fun complete(snapshot: BracketSnapshot): AnalysisAcceptance {
            val stamp = snapshot.stamp
            val remainingRefusal =
                refusal?.takeUnless { rejected ->
                    stamp.covers(rejected.stamp)
                }
            return AnalysisAcceptance(snapshot, stamp, remainingRefusal)
        }

        companion object {
            val EMPTY = AnalysisAcceptance()
        }
    }

    private data class AnalysisRefusal(
        val stamp: AnalysisStamp,
        val limit: AnalysisLimit,
        val completedStamp: AnalysisStamp? = null,
    )

    private companion object {
        private val ACTIVE_PAIR_COVERAGE =
            AnalysisCoverage(
                tokens = false,
                activePair = true,
                guidePosition = false,
            )
        private val TOKEN_COVERAGE =
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            )
    }
}
