package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.DocumentBracketIndexes
import com.sijunyang.bracketpairguides.analysis.snapshot.stampedOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** IntelliJ composition of token recognition and immutable snapshot policy. */
@Service(Service.Level.APP)
internal class BracketAnalysis {
    private val documentIndexes = DocumentBracketIndexes()

    /** Called by an independently dispatched editor job; no outer read lock may be held. */
    suspend fun analyzeInBackground(input: AnalysisInput): AnalysisOutcome? {
        val context = currentCoroutineContext()
        val checkCanceled = {
            context.ensureActive()
            ProgressManager.checkCanceled()
        }
        val calculated =
            IncrementalAnalysis(input, service<AnalysisReadEpoch>(), checkCanceled).calculate() ?: return null
        return stampedOutcome(input, calculated) { snapshotInput, layout, pairs, indexes ->
            documentIndexes.canonical(snapshotInput, layout, pairs, indexes, checkCanceled)
        }
    }
}
