package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import org.jetbrains.annotations.ApiStatus

/** Immutable editor and configuration identity behind one bracket analysis. */
@ApiStatus.Internal
public class AnalysisStamp internal constructor(
    editor: Editor,
    private val fileType: FileType,
    public val coverage: AnalysisCoverage,
    private val disabledLanguageIds: Set<String>,
) {
    private val documentStamp: Long = editor.document.modificationStamp
    internal val tabSize: Int =
        editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    private val highlighter: Any = editor.highlighter

    public fun covers(required: AnalysisStamp): Boolean =
        documentStamp == required.documentStamp &&
            (!required.coverage.guidePosition || tabSize == required.tabSize) &&
            highlighter === required.highlighter &&
            fileType === required.fileType &&
            disabledLanguageIds == required.disabledLanguageIds &&
            coverage.includes(required.coverage)

    /** Checks live editor state without allocating another stamp. */
    public fun matchesCurrent(
        editor: Editor,
        requiredFileType: FileType,
        requiredCoverage: AnalysisCoverage,
        requiredDisabledLanguageIds: Set<String>,
    ): Boolean =
        documentStamp == editor.document.modificationStamp &&
            (!requiredCoverage.guidePosition ||
                tabSize == editor.settings.getTabSize(editor.project).coerceAtLeast(1)) &&
            highlighter === editor.highlighter &&
            fileType === requiredFileType &&
            disabledLanguageIds == requiredDisabledLanguageIds &&
            coverage.includes(requiredCoverage)

}
