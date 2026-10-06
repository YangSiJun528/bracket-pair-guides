package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType

/** Captures only lightweight editor identity; calculation input capture belongs to runtime. */
fun AnalysisStamp(
    editor: Editor,
    fileType: FileType,
    coverage: AnalysisCoverage,
    disabledLanguageIds: Set<String>,
): AnalysisStamp = AnalysisStamp(
    documentStamp = editor.document.modificationStamp,
    fileType = fileType,
    coverage = coverage,
    disabledLanguageIds = disabledLanguageIds,
    tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1),
    highlighter = editor.highlighter,
)

fun AnalysisStamp.matchesCapturedSource(editor: Editor, requiredFileType: FileType): Boolean =
    matchesCapturedSource(editor.document.modificationStamp, editor.highlighter, requiredFileType)

fun AnalysisStamp.matchesCurrent(
    editor: Editor,
    requiredFileType: FileType,
    requiredCoverage: AnalysisCoverage,
    requiredDisabledLanguageIds: Set<String>,
): Boolean {
    val documentStamp = editor.document.modificationStamp
    val highlighter = editor.highlighter
    if (!matchesCapturedSource(documentStamp, highlighter, requiredFileType)) return false
    val currentTabSize = if (requiredCoverage.guidePosition) {
        editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    } else {
        tabSize
    }
    return matchesCurrent(
        documentStamp,
        highlighter,
        requiredFileType,
        requiredCoverage,
        requiredDisabledLanguageIds,
        currentTabSize,
    )
}
