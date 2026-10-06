package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** Runs the actual suspend API on Default while the fixture EDT dispatches platform retries. */
internal class BackgroundAnalysisTestScope : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun analyze(input: AnalysisInput, analysis: BracketAnalysis = BracketAnalysis()): AnalysisOutcome = checkNotNull(
        await {
            analysis.analyzeInBackground(input)
        },
    ) { "Current fixture input was unexpectedly invalidated" }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> await(action: suspend () -> T): T {
        check(ApplicationManager.getApplication().isDispatchThread)
        val worker = scope.async { action() }
        try {
            PlatformTestUtil.waitWithEventsDispatching("actual background analysis finishes", {
                worker.isCompleted
            }, 30)
            return worker.getCompleted()
        } finally {
            worker.cancel()
        }
    }

    override fun close() {
        scope.cancel()
        PlatformTestUtil.waitWithEventsDispatching(
            "background analysis fixture scope stops",
            { scope.coroutineContext[Job]!!.children.none() },
            30,
        )
    }
}
