package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.editor.Editor

/** Shared fresh harness services, identical for baseline and candidate. */
interface ComparisonFixture {
    val host: ComparisonHost
    val warmups: Int
    val repeats: Int
    val cancellationTrials: Int
    suspend fun editor(name: String, text: String, mode: String = "all"): Editor
    suspend fun release(editor: Editor)
    suspend fun edit(editor: Editor, offset: Int, length: Int, replacement: String)

    /** Records direct worker wall/allocation and actual reads; returned normalization belongs outside timing. */
    suspend fun <T> measure(
        workload: String,
        corpus: String,
        iteration: Int,
        metadata: Map<String, Any?> = emptyMap(),
        reads: ReadRecorder? = null,
        operation: suspend () -> T,
    ): T
    fun emit(vararg fields: Pair<String, Any?>)
}
