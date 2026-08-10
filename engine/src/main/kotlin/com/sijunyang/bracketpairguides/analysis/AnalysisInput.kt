package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.LinkedHashSet

/** Immutable input captured for one background analysis pass. */
@ApiStatus.Internal
public class AnalysisInput(
    public val editor: Editor,
    public val fileType: FileType,
    public val coverage: AnalysisCoverage,
    disabledLanguageIds: Set<String>,
) {
    public val disabledLanguageIds: Set<String> = immutableCopy(disabledLanguageIds)

    public val stamp: AnalysisStamp = AnalysisStamp(
        editor = editor,
        fileType = fileType,
        coverage = coverage,
        disabledLanguageIds = this.disabledLanguageIds,
    )
}

private fun <T> immutableCopy(values: Set<T>): Set<T> =
    if (values.isEmpty()) emptySet() else Collections.unmodifiableSet(LinkedHashSet(values))
