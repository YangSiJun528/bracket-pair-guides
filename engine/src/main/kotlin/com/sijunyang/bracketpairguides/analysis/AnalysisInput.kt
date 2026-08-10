package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.LinkedHashSet

/** Immutable input captured for one background analysis pass. */
@ApiStatus.Internal
public class AnalysisInput {
    public val editor: Editor
    public val fileType: FileType
    public val coverage: AnalysisCoverage
    public val disabledLanguageIds: Set<String>
    public val stamp: AnalysisStamp

    public constructor(
        editor: Editor,
        fileType: FileType,
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
    ) {
        val immutableLanguageIds = immutableCopy(disabledLanguageIds)
        this.editor = editor
        this.fileType = fileType
        this.coverage = coverage
        this.disabledLanguageIds = immutableLanguageIds
        this.stamp = AnalysisStamp(
            editor = editor,
            fileType = fileType,
            coverage = coverage,
            disabledLanguageIds = immutableLanguageIds,
        )
    }

    private constructor(
        editor: Editor,
        fileType: FileType,
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
        stamp: AnalysisStamp,
    ) {
        require(stamp.coverage == coverage) {
            "Input coverage must match its captured stamp"
        }
        this.editor = editor
        this.fileType = fileType
        this.coverage = coverage
        this.disabledLanguageIds = disabledLanguageIds
        this.stamp = stamp
    }

    internal fun withCoverage(nextCoverage: AnalysisCoverage): AnalysisInput =
        if (nextCoverage == coverage) {
            this
        } else {
            AnalysisInput(
                editor = editor,
                fileType = fileType,
                coverage = nextCoverage,
                disabledLanguageIds = disabledLanguageIds,
                stamp = stamp.withCoverage(nextCoverage),
            )
        }
}

private fun <T> immutableCopy(values: Set<T>): Set<T> =
    if (values.isEmpty()) emptySet() else Collections.unmodifiableSet(LinkedHashSet(values))
