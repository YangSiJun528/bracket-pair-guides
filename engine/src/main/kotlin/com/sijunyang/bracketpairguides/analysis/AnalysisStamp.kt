package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import org.jetbrains.annotations.ApiStatus

/** Immutable editor and configuration identity behind one bracket analysis. */
@ApiStatus.Internal
public class AnalysisStamp private constructor(
    private val documentStamp: Long,
    private val fileType: FileType,
    public val coverage: AnalysisCoverage,
    private val disabledLanguageIds: Set<String>,
    internal val tabSize: Int,
    private val highlighter: Any,
) {
    internal constructor(
        editor: Editor,
        fileType: FileType,
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
    ) : this(
        documentStamp = editor.document.modificationStamp,
        fileType = fileType,
        coverage = coverage,
        disabledLanguageIds = disabledLanguageIds,
        tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1),
        highlighter = editor.highlighter,
    )

    /** Preserves the captured input identity while narrowing its requested facets. */
    internal fun withCoverage(nextCoverage: AnalysisCoverage): AnalysisStamp {
        require(coverage.includes(nextCoverage)) {
            "Derived coverage must not exceed captured coverage"
        }
        return if (nextCoverage == coverage) {
            this
        } else {
            AnalysisStamp(
                documentStamp = documentStamp,
                fileType = fileType,
                coverage = nextCoverage,
                disabledLanguageIds = disabledLanguageIds,
                tabSize = tabSize,
                highlighter = highlighter,
            )
        }
    }

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
