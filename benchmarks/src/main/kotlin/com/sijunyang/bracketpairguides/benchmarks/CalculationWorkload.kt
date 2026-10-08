package com.sijunyang.bracketpairguides.benchmarks

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.DocumentFacts
import com.sijunyang.bracketpairguides.core.input.PrefixBatch
import com.sijunyang.bracketpairguides.core.input.PrefixChunk
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.core.input.StructuralRole
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.core.input.TokenRole
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException

/** Benchmark-only adapter for public use cases; never exposes private calculation stages. */
class CalculationWorkload(pairCount: Int, distribution: String) {
    private val text = when (distribution) {
        "nested" -> "{".repeat(pairCount) + "\n  value\n" + "}".repeat(pairCount)
        "sparse" -> "{\n" + "  ordinary text without brackets\n".repeat(pairCount) + "}\n"
        "malformed" -> "{]\n".repeat(pairCount)
        else -> "{\n  value\n}\n".repeat(pairCount)
    }
    private val input = RecordedInput(text)
    private val coverage = AnalysisCoverage(tokens = true, activePair = true, guidePosition = true)
    private val calculator = BracketCalculator()
    private val control = object : CalculationControl {
        override fun checkCanceled() = Unit
        override suspend fun yieldWork() = Unit
    }
    private val ready = runBlocking { calculator.analyze(input, coverage, control) }

    fun analyzeCold(): AnalysisResult = runBlocking { BracketCalculator().analyze(input, coverage, control) }
    fun analyzeReuse(): AnalysisResult = runBlocking { calculator.analyze(input, coverage, control) }
    fun query(): Int {
        val result = ready as? AnalysisResult.Available ?: return -1
        val window = result.view.visibleTokens(OffsetRange(0, minOf(text.length, 1024)), 1, 2048)
        var checksum = window.size
        for (index in 0 until window.size) checksum = checksum xor window.offsetAt(index) xor window.depthAt(index)
        return checksum
    }
    fun repair(): Any? = runBlocking {
        val result = ready as? AnalysisResult.Available ?: return@runBlocking null
        val pair = result.view.activePairAt(1) ?: return@runBlocking null
        calculator.repair(input, RepairRequest(pair, exact = true), control)
    }
    fun cancel(): Boolean {
        var checkpoints = 0
        val cancelled = object : CalculationControl {
            override fun checkCanceled() {
                if (++checkpoints == 8) throw CancellationException()
            }
            override suspend fun yieldWork() = checkCanceled()
        }
        return try {
            runBlocking { BracketCalculator().analyze(input, coverage, cancelled) }
            false
        } catch (_: CancellationException) {
            true
        }
    }

    /** Independent comparison called by the external Java fingerprint harness, outside JMH measurements. */
    @Suppress("unused")
    fun semanticFingerprint(): String {
        fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val available = ready as? AnalysisResult.Available
        val limit = when (val outcome = ready) {
            is AnalysisResult.Available -> outcome.limit
            is AnalysisResult.Unavailable -> outcome.limit
        }
        return buildString {
            appendLine("text=${digest(text)}")
            appendLine("length=${text.length}")
            appendLine("lines=${input.lineCount}")
            appendLine("state=${if (available == null) "unavailable" else "available"}")
            appendLine("coverage=${ready.coverage}")
            appendLine("matcher=${ready.matcherAvailability.name}")
            appendLine("limit=$limit")
            if (available != null) {
                val all = available.view.visibleTokens(OffsetRange(0, text.length), 1, Int.MAX_VALUE)
                val contents = buildString {
                    for (index in 0 until all.size) {
                        append(
                            "${all.offsetAt(index)},${all.lengthAt(index)},${all.depthAt(index)};",
                        )
                    }
                }
                appendLine("tokens=${all.size}:${all.isCapped}:${digest(contents)}")
                val offsets = listOf(
                    0,
                    1,
                    text.length / 4,
                    text.length / 2,
                    3 * text.length / 4,
                    text.length - 1,
                    text.length,
                )
                    .map { it.coerceIn(0, text.length) }.distinct().sorted()
                for (offset in offsets) {
                    val pair = available.view.activePairAt(offset)
                    appendLine("sample=$offset:$pair:${pair?.let(available.view::guideFor)}")
                }
            }
            appendLine("repair=${repair()}")
            appendLine("query=${query()}")
            appendLine("cancel=${cancel()}")
        }
    }

    private class RecordedInput(private val text: String) : BracketInput {
        private val starts = buildList {
            add(0)
            text.forEachIndexed { index, ch -> if (ch == '\n') add(index + 1) }
        }
        val lineCount: Int get() = starts.size
        private val open = TokenKind()
        private val close = TokenKind()
        private val group = TokenGroup(0, 0)
        override suspend fun beginAttempt() = DocumentFacts(text.length, starts.size, 4, 0)
        override suspend fun validateCurrent() = Unit
        override suspend fun areCompatible(open: TokenKind, close: TokenKind, group: TokenGroup): Boolean =
            open === this.open && close === this.close
        override suspend fun tokensAt(offset: Int): TokenBatch = TokenBatch.capture { collector ->
            var cursor = offset
            var visited = 0
            while (cursor < text.length && visited < 512) {
                val character = text[cursor]
                if (character == '{' || character == '}') {
                    val opening = character == '{'
                    val line = starts.binarySearch(cursor).let { if (it >= 0) it else -it - 2 }
                    collector.append(
                        if (opening) open else close, group, null, false,
                        if (opening) TokenRole.OPEN else TokenRole.CLOSE,
                        if (opening) StructuralRole.OPEN else StructuralRole.CLOSE,
                        cursor, 1, line,
                    )
                }
                cursor++
                visited++
            }
            TokenBatch.End(cursor, cursor == text.length, visited, BraceMatcherAvailability.AVAILABLE)
        }
        override suspend fun initialPrefixes(firstLine: Int, lineCount: Int): PrefixBatch = PrefixBatch(
            firstLine,
            (firstLine until minOf(starts.size, firstLine + lineCount)).map { prefix(it, starts[it], 128) },
        )
        override suspend fun continuePrefix(line: Int, afterOffset: Int): PrefixChunk = prefix(line, afterOffset, 4096)
        private fun prefix(line: Int, offset: Int, maximum: Int): PrefixChunk {
            val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
            val stop = minOf(end, offset + maximum)
            return PrefixChunk(text.substring(offset, stop), stop, end)
        }
    }
}
