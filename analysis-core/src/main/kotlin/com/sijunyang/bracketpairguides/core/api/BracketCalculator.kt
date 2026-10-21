package com.sijunyang.bracketpairguides.core.api

import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.LineInput
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.core.input.RetryCapture
import com.sijunyang.bracketpairguides.core.internal.CalculationAttempt
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.result.AnalysisResult

/** Stateless calculation use cases; each attempt owns its input and result storage. */
class BracketCalculator {
    suspend fun analyze(input: BracketInput, coverage: AnalysisCoverage, control: CalculationControl): AnalysisResult {
        while (true) {
            control.checkCanceled()
            try {
                return CalculationAttempt(control).analyze(input, coverage)
            } catch (_: RetryCapture) {
                // No mutable pairing, builder, or demanded-rule state survives an invalidated capture epoch.
                control.checkCanceled()
                control.yieldWork()
            }
        }
    }

    suspend fun repair(input: LineInput, request: RepairRequest, control: CalculationControl): BracketGuide? {
        while (true) {
            control.checkCanceled()
            try {
                return CalculationAttempt(control).repair(input, request)
            } catch (_: RetryCapture) {
                control.checkCanceled()
                control.yieldWork()
            }
        }
    }
}
