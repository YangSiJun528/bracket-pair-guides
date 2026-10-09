package com.sijunyang.bracketpairguides.core.input

import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability

/** Opaque identity owned by one input attempt; it never contains a host object. */
class TokenKind

data class TokenGroup(val languageOrdinal: Int, val tokenGroup: Int)
enum class TokenRole { OPEN, CLOSE, TOGGLE }
enum class StructuralRole {
    NONE,
    OPEN,
    CLOSE,
    OPEN_AND_CLOSE,
    ;

    companion object {
        fun of(opens: Boolean, closes: Boolean): StructuralRole = if (opens) {
            if (closes) OPEN_AND_CLOSE else OPEN
        } else {
            if (closes) CLOSE else NONE
        }
    }
}

/** A capture-only sink, revoked when its capture callback returns. */
interface TokenCollector {
    fun append(
        kind: TokenKind,
        group: TokenGroup,
        context: String?,
        strictContext: Boolean,
        role: TokenRole,
        structuralRole: StructuralRole,
        offset: Int,
        tokenLength: Int,
        line: Int,
    )
}

/** Bounded immutable primitive columns; sealing transfers storage without per-token objects. */
class TokenBatch private constructor(
    private val kinds: Array<TokenKind?>,
    private val commonGroup: TokenGroup?,
    private val groups: Array<TokenGroup?>?,
    private val contexts: Array<String?>?,
    private val geometry: IntArray,
    val size: Int,
    val nextOffset: Int,
    val end: Boolean,
    val visitedTokens: Int,
    val matcherAvailability: BraceMatcherAvailability,
    val sourceAvailable: Boolean,
) {
    internal fun kindAt(index: Int): TokenKind = checkNotNull(kinds[index])
    internal fun groupAt(index: Int): TokenGroup = checkNotNull(groups?.get(index) ?: commonGroup)
    internal fun contextAt(index: Int): String? = contexts?.get(index)
    internal fun offsetAt(index: Int): Int = geometry[index * 4]
    internal fun lengthAt(index: Int): Int = geometry[index * 4 + 1]
    internal fun lineAt(index: Int): Int = geometry[index * 4 + 2]
    internal fun flagsAt(index: Int): Int = geometry[index * 4 + 3]

    data class End(
        val nextOffset: Int,
        val end: Boolean,
        val visitedTokens: Int,
        val matcherAvailability: BraceMatcherAvailability,
        val sourceAvailable: Boolean = true,
    )

    companion object {
        private val EMPTY_KINDS = emptyArray<TokenKind?>()
        private val EMPTY_GEOMETRY = IntArray(0)

        fun capture(expectedTokens: Int = 32, capture: (TokenCollector) -> End): TokenBatch {
            val collector = Collector(expectedTokens.coerceIn(1, 512))
            return try {
                collector.seal(capture(collector))
            } finally {
                collector.revoke()
            }
        }
    }

    private class Collector(private val expected: Int) : TokenCollector {
        private var kinds = EMPTY_KINDS
        private var commonGroup: TokenGroup? = null
        private var groups: Array<TokenGroup?>? = null
        private var contexts: Array<String?>? = null
        private var geometry = EMPTY_GEOMETRY
        private var size = 0
        private var revoked = false

        override fun append(
            kind: TokenKind,
            group: TokenGroup,
            context: String?,
            strictContext: Boolean,
            role: TokenRole,
            structuralRole: StructuralRole,
            offset: Int,
            tokenLength: Int,
            line: Int,
        ) {
            check(!revoked) { "A completed capture cannot be changed" }
            require(size < 512 && offset >= 0 && tokenLength > 0 && line >= 0)
            if (size == kinds.size) {
                val capacity = if (size == 0) expected else minOf(512, size * 2)
                kinds = kinds.copyOf(capacity)
                groups = groups?.copyOf(capacity)
                contexts = contexts?.copyOf(capacity)
                geometry = geometry.copyOf(capacity * 4)
            }
            if (size == 0) {
                commonGroup = group
            } else if (groups == null && group !== commonGroup) {
                groups = arrayOfNulls<TokenGroup>(kinds.size).also { it.fill(commonGroup, 0, size) }
            }
            if (contexts == null && context != null) contexts = arrayOfNulls(kinds.size)
            kinds[size] = kind
            groups?.set(size, group)
            contexts?.set(size, context)
            val position = size * 4
            geometry[position] = offset
            geometry[position + 1] = tokenLength
            geometry[position + 2] = line
            geometry[position + 3] = role.ordinal or (structuralRole.ordinal shl 2) or (if (strictContext) 16 else 0)
            size++
        }

        fun seal(end: End): TokenBatch {
            check(!revoked)
            require(end.nextOffset >= 0 && end.visitedTokens in size..512)
            revoke()
            return TokenBatch(
                kinds, commonGroup, groups, contexts, geometry, size, end.nextOffset,
                end.end, end.visitedTokens, end.matcherAvailability, end.sourceAvailable,
            )
        }
        fun revoke() {
            revoked = true
        }
    }
}
