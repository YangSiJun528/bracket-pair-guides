package com.sijunyang.bracketpairguides.analysis

import java.util.Collections
import java.util.LinkedHashSet

/** Immutable editor and configuration identity behind one bracket analysis. */
class AnalysisStamp private constructor(
    val documentStamp: Long,
    private val fileType: Any,
    val coverage: AnalysisCoverage,
    val disabledLanguageIds: Set<String>,
    val tabSize: Int,
    private val highlighter: Any,
    @Suppress("UNUSED_PARAMETER") immutable: Unit,
) {
    constructor(
        documentStamp: Long,
        fileType: Any,
        coverage: AnalysisCoverage,
        disabledLanguageIds: Set<String>,
        tabSize: Int,
        highlighter: Any,
    ) : this(
        documentStamp,
        fileType,
        coverage,
        if (disabledLanguageIds.isEmpty()) {
            emptySet()
        } else {
            Collections.unmodifiableSet(
                LinkedHashSet(disabledLanguageIds),
            )
        },
        tabSize,
        highlighter,
        Unit,
    )

    /** Preserves the captured input identity while narrowing its requested facets. */
    fun withCoverage(nextCoverage: AnalysisCoverage): AnalysisStamp {
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
                immutable = Unit,
            )
        }
    }

    fun covers(required: AnalysisStamp): Boolean = documentStamp == required.documentStamp &&
        (!required.coverage.guidePosition || tabSize == required.tabSize) &&
        highlighter === required.highlighter &&
        fileType === required.fileType &&
        disabledLanguageIds == required.disabledLanguageIds &&
        coverage.includes(required.coverage)

    /** Source consistency using identities captured by a host adapter. */
    fun matchesCapturedSource(documentStamp: Long, highlighter: Any, requiredFileType: Any): Boolean =
        this.documentStamp == documentStamp && this.highlighter === highlighter && fileType === requiredFileType

    /** Checks captured host facts without allocating another stamp. */
    fun matchesCurrent(
        documentStamp: Long,
        highlighter: Any,
        requiredFileType: Any,
        requiredCoverage: AnalysisCoverage,
        requiredDisabledLanguageIds: Set<String>,
        currentTabSize: Int,
    ): Boolean = matchesCapturedSource(documentStamp, highlighter, requiredFileType) &&
        (!requiredCoverage.guidePosition || tabSize == currentTabSize) &&
        disabledLanguageIds == requiredDisabledLanguageIds && coverage.includes(requiredCoverage)
}
