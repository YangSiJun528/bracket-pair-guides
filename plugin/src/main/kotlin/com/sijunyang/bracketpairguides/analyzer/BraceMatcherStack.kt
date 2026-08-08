package com.sijunyang.bracketpairguides.analyzer

/**
 * One-pass pairing state for language brace matchers.
 *
 * Normal closes are O(1). Before malformed-input recovery scans a stack,
 * active token/context counts reject unrelated closes. Any recovery scan
 * discards the visited openers, so total stack traversal remains amortized
 * linear in the token count. Structural openers split regular-brace scopes:
 * structural pairs may recover across a scope boundary, regular pairs may not.
 * An unmatched structural opener is conservatively kept as a boundary because
 * this streaming API cannot know whether it will match later. Bounded active
 * lookup may track one candidate offset; full analysis retains no offset map.
 */
internal class BraceMatcherStack<T, G>(
    private val trackedOpenOffset: Int? = null,
) {
    data class Open<T>(
        val token: T,
        val context: String?,
        val strictContext: Boolean,
        val structural: Boolean,
        val offset: Int,
        val tokenLength: Int,
        val line: Int,
        val depth: Int,
    )

    data class Match<T>(val open: Open<T>)

    private data class ContextKey<T>(val token: T, val context: String?)

    private class Counts<T> {
        var tokenCounts: HashMap<T, Int>? = null
        var contextCounts: HashMap<ContextKey<T>, Int>? = null
    }

    private class State<T> {
        val stack = ArrayDeque<Open<T>>()
        val allCounts = Counts<T>()
        val regularScopes = ArrayDeque<Counts<T>>().apply {
            addLast(Counts())
        }
        var structuralOpenCount = 0
    }

    private val states = HashMap<G, State<T>>()
    private var trackedOpenCount = 0

    fun open(
        group: G,
        token: T,
        context: String?,
        offset: Int,
        tokenLength: Int,
        line: Int,
        strictContext: Boolean = false,
        structural: Boolean = false,
    ) {
        val state = states.getOrPut(group) { State() }
        val open = Open(
            token = token,
            context = context,
            strictContext = strictContext,
            structural = structural,
            offset = offset,
            tokenLength = tokenLength,
            line = line,
            depth = state.stack.size,
        )
        state.stack += open
        if (offset == trackedOpenOffset) trackedOpenCount++
        increment(state.allCounts, open)
        if (structural) {
            state.structuralOpenCount++
            state.regularScopes.addLast(Counts())
        } else {
            increment(state.regularScopes.last(), open)
        }
    }

    fun containsOpenAt(offset: Int): Boolean =
        offset == trackedOpenOffset && trackedOpenCount > 0

    fun close(
        group: G,
        token: T,
        context: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
        isStructuralPair: (T, T) -> Boolean = { _, _ -> false },
        checkCanceled: () -> Unit = {},
        canCloseStructural: Boolean = true,
    ): Match<T>? {
        val state = states[group] ?: return null

        val top = state.stack.last()
        val topMatches = matches(top, token, context, strictContext, isPair)
        if (topMatches && isStructuralPair(top.token, token)) {
            removeLast(state)
            removeGroupIfEmpty(group, state)
            return Match(top)
        }

        if (canCloseStructural && state.structuralOpenCount > 0 &&
            hasCandidate(
                counts = state.allCounts,
                closeToken = token,
                closeContext = context,
                strictContext = strictContext,
                isPair = isPair,
                isStructuralPair = isStructuralPair,
                structural = true,
                checkCanceled = checkCanceled,
            )
        ) {
            return recover(
                group = group,
                state = state,
                closeToken = token,
                closeContext = context,
                strictContext = strictContext,
                isPair = isPair,
                isStructuralPair = isStructuralPair,
                structural = true,
                checkCanceled = checkCanceled,
            )
        }

        if (topMatches) {
            removeLast(state)
            removeGroupIfEmpty(group, state)
            return Match(top)
        }

        if (!hasCandidate(
                counts = state.regularScopes.last(),
                closeToken = token,
                closeContext = context,
                strictContext = strictContext,
                isPair = isPair,
                isStructuralPair = isStructuralPair,
                structural = false,
                checkCanceled = checkCanceled,
            )
        ) {
            return null
        }

        return recover(
            group = group,
            state = state,
            closeToken = token,
            closeContext = context,
            strictContext = strictContext,
            isPair = isPair,
            isStructuralPair = isStructuralPair,
            structural = false,
            checkCanceled = checkCanceled,
        )
    }

    private fun hasCandidate(
        counts: Counts<T>,
        closeToken: T,
        closeContext: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
        isStructuralPair: (T, T) -> Boolean,
        structural: Boolean,
        checkCanceled: () -> Unit,
    ): Boolean {
        var visitedTypes = 0
        for ((openToken, count) in counts.tokenCounts ?: return false) {
            if (visitedTypes++ and CANCELLATION_MASK == 0) checkCanceled()
            if (count == 0 || !isPair(openToken, closeToken)) continue
            if (isStructuralPair(openToken, closeToken) != structural) continue
            if (!strictContext ||
                (counts.contextCounts?.get(ContextKey(openToken, closeContext)) ?: 0) > 0
            ) {
                return true
            }
        }
        return false
    }

    private fun recover(
        group: G,
        state: State<T>,
        closeToken: T,
        closeContext: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
        isStructuralPair: (T, T) -> Boolean,
        structural: Boolean,
        checkCanceled: () -> Unit,
    ): Match<T>? {
        var discarded = 0
        while (state.stack.isNotEmpty()) {
            if (!structural && state.stack.last().structural) return null
            if (discarded++ and CANCELLATION_MASK == 0) checkCanceled()
            val open = removeLast(state)
            if (matches(open, closeToken, closeContext, strictContext, isPair) &&
                isStructuralPair(open.token, closeToken) == structural
            ) {
                removeGroupIfEmpty(group, state)
                return Match(open)
            }
        }

        states.remove(group)
        return null
    }

    private fun matches(
        open: Open<T>,
        closeToken: T,
        closeContext: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
    ): Boolean = isPair(open.token, closeToken) &&
        (!strictContext || (open.strictContext && open.context == closeContext))

    private fun removeLast(state: State<T>): Open<T> {
        val open = state.stack.removeLast()
        if (open.offset == trackedOpenOffset) trackedOpenCount--
        decrement(state.allCounts, open)
        if (open.structural) {
            state.structuralOpenCount--
            state.regularScopes.removeLast()
        } else {
            decrement(state.regularScopes.last(), open)
        }
        return open
    }

    private fun increment(counts: Counts<T>, open: Open<T>) {
        val tokenCounts = counts.tokenCounts
            ?: HashMap<T, Int>().also { counts.tokenCounts = it }
        increment(tokenCounts, open.token)
        if (open.strictContext) {
            val contextCounts = counts.contextCounts
                ?: HashMap<ContextKey<T>, Int>().also { counts.contextCounts = it }
            increment(contextCounts, ContextKey(open.token, open.context))
        }
    }

    private fun decrement(counts: Counts<T>, open: Open<T>) {
        counts.tokenCounts?.let { tokenCounts ->
            decrement(tokenCounts, open.token)
        }
        if (open.strictContext) {
            counts.contextCounts?.let { contextCounts ->
                decrement(contextCounts, ContextKey(open.token, open.context))
            }
        }
    }

    private fun removeGroupIfEmpty(group: G, state: State<T>) {
        if (state.stack.isEmpty()) states.remove(group)
    }

    private fun <K> increment(counts: HashMap<K, Int>, key: K) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    private fun <K> decrement(counts: HashMap<K, Int>, key: K) {
        val remaining = (counts[key] ?: return) - 1
        if (remaining == 0) {
            counts.remove(key)
        } else {
            counts[key] = remaining
        }
    }

    private companion object {
        const val CANCELLATION_MASK = 0xFF
    }
}
