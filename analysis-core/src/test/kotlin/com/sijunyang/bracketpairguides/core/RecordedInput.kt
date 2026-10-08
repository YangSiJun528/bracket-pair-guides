package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.DocumentFacts
import com.sijunyang.bracketpairguides.core.input.PrefixBatch
import com.sijunyang.bracketpairguides.core.input.PrefixChunk
import com.sijunyang.bracketpairguides.core.input.StructuralRole
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.core.input.TokenRole
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** In-memory adapter for the same bounded input contract as the IntelliJ adapter. */
internal class RecordedInput(
    val text: String,
    var revision: Long = 1,
    private val chunk: Int = 512,
    private val tabSize: Int = 4,
) : BracketInput {
    private val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    private val ends = starts.map { first -> text.indexOf('\n', first).let { if (it < 0) text.length else it } }
    private val kinds = HashMap<Char, TokenKind>()
    private val symbols = HashMap<TokenKind, Char>()
    private val group = TokenGroup(0, 0)
    var attempts = 0
    var tokenCaptures = 0
    var compatibilityReads = 0
    var continuationReads = 0
    var unavailable = false
    var onTokens: suspend (Int) -> Unit = {}
    var onValidate: suspend () -> Unit = {}
    val prefixRequests = ArrayList<Pair<Int, Int>>()

    override suspend fun beginAttempt(): DocumentFacts {
        attempts++
        kinds.clear()
        symbols.clear()
        return DocumentFacts(text.length, starts.size, tabSize, revision)
    }
    override suspend fun tokensAt(offset: Int): TokenBatch {
        tokenCaptures++
        onTokens(offset)
        if (unavailable) {
            return TokenBatch.capture {
                TokenBatch.End(
                    text.length,
                    true,
                    0,
                    BraceMatcherAvailability.UNDETERMINED,
                    sourceAvailable = false,
                )
            }
        }
        val after = minOf(text.length, offset + chunk)
        return TokenBatch.capture { collector ->
            for (position in offset until after) {
                val character = text[position]
                if (character !in "{}[]()") continue
                val kind = kinds.getOrPut(character) { TokenKind().also { symbols[it] = character } }
                val line = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }
                collector.append(
                    kind, group, null, false,
                    if (character in "{[(") TokenRole.OPEN else TokenRole.CLOSE,
                    StructuralRole.NONE, position, 1, line,
                )
            }
            TokenBatch.End(after, after == text.length, after - offset, BraceMatcherAvailability.AVAILABLE)
        }
    }
    override suspend fun areCompatible(open: TokenKind, close: TokenKind, group: TokenGroup): Boolean {
        compatibilityReads++
        return when (symbols[open]) {
            '{' -> symbols[close] == '}'

            '[' -> symbols[close] == ']'

            '(' -> symbols[close] ==
                ')'

            else -> false
        }
    }
    override suspend fun initialPrefix(line: Int): PrefixChunk {
        prefixRequests += line to 1
        return prefix(line)
    }
    override suspend fun initialPrefixes(firstLine: Int, lineCount: Int): PrefixBatch {
        require(lineCount in 1..128)
        prefixRequests += firstLine to lineCount
        return PrefixBatch(firstLine, (firstLine until firstLine + lineCount).map(::prefix))
    }
    private fun prefix(line: Int): PrefixChunk {
        val after = minOf(starts[line] + 128, ends[line])
        return PrefixChunk(text.substring(starts[line], after), after, ends[line])
    }
    override suspend fun continuePrefix(line: Int, afterOffset: Int): PrefixChunk {
        continuationReads++
        val after = minOf(afterOffset + 4096, ends[line])
        return PrefixChunk(text.substring(afterOffset, after), after, ends[line])
    }
    override suspend fun validateCurrent() = onValidate()
}

internal suspend fun coroutineControl(): CalculationControl {
    val context = currentCoroutineContext()
    return object : CalculationControl {
        override fun checkCanceled() = context.ensureActive()
        override suspend fun yieldWork() = yield()
    }
}
