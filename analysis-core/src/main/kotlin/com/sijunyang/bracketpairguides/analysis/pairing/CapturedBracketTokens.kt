package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole

/** Immutable token identity, with no platform reference. Equality intentionally uses identity. */
class TokenKind

/** Preserves token-language identity and the matcher's original numeric group without host references. */
data class BracketGroupId(val languageOrdinal: Int, val tokenGroup: Int)

/** One platform-classified occurrence that can be paired without holding read access. */
data class CapturedBracketToken(
    val kind: TokenKind,
    val group: BracketGroupId,
    val context: String?,
    val strictContext: Boolean,
    val role: BracketRole,
    val structuralRole: StructuralRole,
    val offset: Int,
    val tokenLength: Int,
    val line: Int,
)

/**
 * Owned immutable columns. Capture transfers private buffers once at seal; consumers cannot mutate
 * them. Pairing reads columns directly, avoiding a heap object for every bracket occurrence.
 * A uniform group is stored once; an all-null context column requires no buffer.
 */
class CapturedBracketTokens private constructor(
    private val kinds: Array<TokenKind?>,
    private val commonGroup: BracketGroupId?,
    private val groups: Array<BracketGroupId?>?,
    private val contexts: Array<String?>?,
    private val geometry: IntArray,
    val size: Int,
    val nextOffset: Int,
    val end: Boolean,
    val visitedTokens: Int,
    val matcherAvailability: BraceMatcherAvailability,
    val sourceAvailable: Boolean,
) {
    /** Convenient immutable value view for diagnostics and direct input assertions. */
    operator fun get(index: Int): CapturedBracketToken {
        require(index in 0 until size)
        val position = index * 4
        val flags = geometry[position + 3]
        return CapturedBracketToken(
            checkNotNull(kinds[index]),
            groupAt(index),
            contexts?.get(index),
            flags and STRICT_CONTEXT != 0,
            ROLES[flags and ROLE_MASK],
            STRUCTURAL_ROLES[(flags ushr STRUCTURAL_SHIFT) and STRUCTURAL_MASK],
            geometry[position], geometry[position + 1], geometry[position + 2],
        )
    }

    /** Starts one occurrence without materializing its value view. */
    fun begin(index: Int, pairing: PairingMachine<TokenKind, BracketGroupId>.Session): PairingMachine.Step {
        require(index in 0 until size)
        val position = index * 4
        val flags = geometry[position + 3]
        return pairing.begin(
            groupAt(index),
            checkNotNull(kinds[index]),
            contexts?.get(index),
            flags and STRICT_CONTEXT != 0,
            ROLES[flags and ROLE_MASK],
            STRUCTURAL_ROLES[(flags ushr STRUCTURAL_SHIFT) and STRUCTURAL_MASK],
            geometry[position], geometry[position + 1], geometry[position + 2],
        )
    }

    private fun groupAt(index: Int): BracketGroupId = checkNotNull(groups?.get(index) ?: commonGroup)

    /** Single-owner capture draft; sealing permanently revokes its mutation interface. */
    class Builder(private val expectedTokens: Int) {
        private var kinds = emptyArray<TokenKind?>()
        private var commonGroup: BracketGroupId? = null
        private var groups: Array<BracketGroupId?>? = null
        private var contexts: Array<String?>? = null
        private var geometry = IntArray(0)
        private var size = 0
        private var sealed = false

        fun append(
            kind: TokenKind,
            group: BracketGroupId,
            context: String?,
            strictContext: Boolean,
            role: BracketRole,
            structuralRole: StructuralRole,
            offset: Int,
            tokenLength: Int,
            line: Int,
        ) {
            check(!sealed) { "A sealed token capture cannot be changed" }
            if (size == kinds.size) {
                val capacity = if (size == 0) expectedTokens.coerceIn(1, 512) else size * 2
                kinds = kinds.copyOf(capacity)
                groups = groups?.copyOf(capacity)
                contexts = contexts?.copyOf(capacity)
                geometry = geometry.copyOf(capacity * 4)
            }
            if (size == 0) {
                commonGroup = group
            } else if (groups == null && group !== commonGroup) {
                // Backfill exactly the preceding occurrences; keep individual group identity.
                groups = arrayOfNulls<BracketGroupId>(kinds.size).also { it.fill(commonGroup, 0, size) }
            }
            if (contexts == null && context != null) contexts = arrayOfNulls(kinds.size)
            kinds[size] = kind
            groups?.set(size, group)
            contexts?.set(size, context)
            val position = size * 4
            geometry[position] = offset
            geometry[position + 1] = tokenLength
            geometry[position + 2] = line
            geometry[position + 3] = role.ordinal or (structuralRole.ordinal shl STRUCTURAL_SHIFT) or
                (if (strictContext) STRICT_CONTEXT else 0)
            size++
        }

        fun seal(
            nextOffset: Int,
            end: Boolean,
            visitedTokens: Int,
            matcherAvailability: BraceMatcherAvailability,
            sourceAvailable: Boolean = true,
        ): CapturedBracketTokens {
            check(!sealed) { "A token capture can be sealed only once" }
            sealed = true
            return CapturedBracketTokens(
                kinds,
                commonGroup,
                groups,
                contexts,
                geometry,
                size,
                nextOffset,
                end,
                visitedTokens,
                matcherAvailability,
                sourceAvailable,
            )
        }
    }

    private companion object {
        val ROLES = BracketRole.entries.toTypedArray()
        val STRUCTURAL_ROLES = StructuralRole.entries.toTypedArray()
        const val ROLE_MASK = 3
        const val STRUCTURAL_MASK = 3
        const val STRUCTURAL_SHIFT = 2
        const val STRICT_CONTEXT = 16
    }
}
