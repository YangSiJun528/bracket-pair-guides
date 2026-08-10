package com.sijunyang.bracketpairguides.analysis

/** Test assertion boundary for scenarios whose input is intentionally below every limit. */
internal fun AnalysisOutcome.requireSnapshot(): BracketSnapshot =
    (this as? AnalysisOutcome.Complete)?.snapshot
        ?: error("Expected complete analysis, got ${this::class.java.simpleName}")
