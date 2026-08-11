package com.sijunyang.bracketpairguides.analysis

import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot

/** Test assertion boundary for scenarios whose input is intentionally below every limit. */
internal fun AnalysisOutcome.requireSnapshot(): BracketSnapshot =
    (this as? AnalysisOutcome.Complete)?.snapshot
        ?: error("Expected complete analysis, got ${this::class.java.simpleName}")
