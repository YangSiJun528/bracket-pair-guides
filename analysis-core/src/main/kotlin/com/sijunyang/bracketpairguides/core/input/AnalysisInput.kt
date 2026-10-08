package com.sijunyang.bracketpairguides.core.input

import com.sijunyang.bracketpairguides.model.BracketPair
import java.util.Collections

/** Host reads return owned immutable facts. Every attempt begins with a fresh capture epoch. */
interface LineInput {
    suspend fun beginAttempt(): DocumentFacts
    suspend fun initialPrefixes(firstLine: Int, lineCount: Int): PrefixBatch
    suspend fun continuePrefix(line: Int, afterOffset: Int): PrefixChunk
    suspend fun validateCurrent()
}

interface BracketInput : LineInput {
    /**
     * Captures at most 512 lexer tokens beginning at the requested exact source boundary.
     * Classified tokens are ordered and nonoverlapping, entirely before nextOffset.
     * Empty batches may advance past non-bracket tokens. An overlong lexer token may advance
     * farther than 512 characters: the budget counts visited tokens, not source characters.
     * A final batch must end exactly at DocumentFacts.length; it cannot publish a partial prefix.
     */
    suspend fun tokensAt(offset: Int): TokenBatch
    suspend fun areCompatible(open: TokenKind, close: TokenKind, group: TokenGroup): Boolean
}

/** Both platform and coroutine cancellation must be represented by the host implementation. */
interface CalculationControl {
    fun checkCanceled()
    suspend fun yieldWork()
}

data class DocumentFacts(val length: Int, val lineCount: Int, val tabSize: Int, val reuseRevision: Long) {
    init {
        require(length >= 0 && lineCount > 0 && tabSize > 0 && reuseRevision >= 0)
    }
}

data class PrefixChunk(val text: String, val afterOffset: Int, val lineEndOffset: Int) {
    init {
        require(afterOffset >= text.length && lineEndOffset >= afterOffset)
    }
    val endOfLine: Boolean get() = afterOffset == lineEndOffset
}

class PrefixBatch(val firstLine: Int, prefixes: List<PrefixChunk>) {
    val prefixes: List<PrefixChunk> = Collections.unmodifiableList(ArrayList(prefixes))
    init {
        require(firstLine >= 0 && prefixes.size <= 128)
    }
}

data class RepairRequest(val pair: BracketPair, val exact: Boolean, val currentAnchorLine: Int? = null)

/** Unrelated host writes invalidate all partial calculation state, but allow a new capture attempt. */
class RetryCapture : RuntimeException(null, null, false, false)
