package com.sijunyang.bracketpairguides.analyzer

/**
 * Pairing state for legacy [com.intellij.codeInsight.highlighting.BraceMatcher]
 * implementations whose decision can depend on iterator context and tag names.
 *
 * Normal well-formed closes are O(1). Before malformed-input recovery scans the
 * stack, active opener token/tag counts reject unrelated closes without walking
 * the full nesting depth.
 */
internal class ContextualBracketStack<T, G> {
    data class Open<T>(
        val token: T,
        val context: String?,
        val offset: Int,
        val tokenLength: Int,
        val line: Int,
        val depth: Int,
    )

    data class Match<T>(val open: Open<T>)

    private data class ContextKey<T>(val token: T, val context: String?)

    private class State<T> {
        val stack = ArrayDeque<Open<T>>()
        val tokenCounts = HashMap<T, Int>()
        val contextCounts = HashMap<ContextKey<T>, Int>()
    }

    private val states = HashMap<G, State<T>>()

    fun open(
        group: G,
        token: T,
        context: String?,
        offset: Int,
        tokenLength: Int,
        line: Int,
    ) {
        val state = states.getOrPut(group) { State() }
        state.stack += Open(
            token = token,
            context = context,
            offset = offset,
            tokenLength = tokenLength,
            line = line,
            depth = state.stack.size,
        )
        increment(state.tokenCounts, token)
        increment(state.contextCounts, ContextKey(token, context))
    }

    fun close(
        group: G,
        token: T,
        context: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
        checkCanceled: () -> Unit = {},
    ): Match<T>? {
        val state = states[group] ?: return null

        val top = state.stack.last()
        if (matches(top, token, context, strictContext, isPair)) {
            state.stack.removeLast()
            decrement(state, top)
            removeGroupIfEmpty(group, state)
            return Match(top)
        }

        if (!hasCandidate(
                state = state,
                closeToken = token,
                closeContext = context,
                strictContext = strictContext,
                isPair = isPair,
                checkCanceled = checkCanceled,
            )
        ) {
            return null
        }

        var discarded = 0
        while (state.stack.isNotEmpty()) {
            if (discarded++ and CANCELLATION_MASK == 0) checkCanceled()
            val open = state.stack.removeLast()
            decrement(state, open)
            if (matches(open, token, context, strictContext, isPair)) {
                removeGroupIfEmpty(group, state)
                return Match(open)
            }
        }

        states.remove(group)
        return null
    }

    private fun hasCandidate(
        state: State<T>,
        closeToken: T,
        closeContext: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
        checkCanceled: () -> Unit,
    ): Boolean {
        return state.tokenCounts.entries.withIndex().any { (index, entry) ->
            if (index and CANCELLATION_MASK == 0) checkCanceled()
            val (openToken, count) = entry
            count != 0 &&
                isPair(openToken, closeToken) &&
                (!strictContext ||
                    (state.contextCounts[ContextKey(openToken, closeContext)] ?: 0) > 0)
        }
    }

    private fun matches(
        open: Open<T>,
        closeToken: T,
        closeContext: String?,
        strictContext: Boolean,
        isPair: (T, T) -> Boolean,
    ): Boolean {
        return isPair(open.token, closeToken) &&
            (!strictContext || open.context == closeContext)
    }

    private fun decrement(state: State<T>, open: Open<T>) {
        decrement(state.tokenCounts, open.token)
        decrement(state.contextCounts, ContextKey(open.token, open.context))
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
