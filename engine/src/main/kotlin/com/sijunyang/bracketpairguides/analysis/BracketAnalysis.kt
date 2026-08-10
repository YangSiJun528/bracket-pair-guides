package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.annotations.ApiStatus

/** Application contract for one synchronous bracket analysis. */
@ApiStatus.Internal
public interface BracketAnalysis {
    /** Performs the requested analysis synchronously in the caller's read action. */
    public fun analyze(
        input: AnalysisInput,
        progress: ProgressIndicator,
    ): AnalysisOutcome
}
