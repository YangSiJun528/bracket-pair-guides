package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import kotlinx.coroutines.Job

/** Editor-bound repair scheduling; validation and publication callbacks run only on EDT. */
internal typealias GuideRepairScheduler = (GuideRepairRequest, () -> Boolean, (BracketGuide) -> Unit) -> Job?

/** Immutable geometry and capture identity supplied by an editor session. */
internal class GuideRepairRequest(
    val pair: BracketPair,
    val stamp: AnalysisStamp,
    val fileType: FileType,
    disabledLanguageIds: Set<String>,
    val exact: Boolean,
    val currentAnchorLine: Int? = null,
) {
    val disabledLanguageIds: Set<String> = java.util.Collections.unmodifiableSet(HashSet(disabledLanguageIds))

    fun matches(editor: Editor): Boolean = !editor.isDisposed && editor.project?.isDisposed != true &&
        stamp.matchesCurrent(editor, fileType, COVERAGE, disabledLanguageIds)

    companion object {
        val COVERAGE = AnalysisCoverage(tokens = false, activePair = true, guidePosition = true)
    }
}
