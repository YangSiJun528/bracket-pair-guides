package com.sijunyang.bracketpairguides.analyzer

/**
 * A small, allocation-conscious pairing state machine.
 *
 * Stacks are isolated per embedded language. On malformed input, a matching
 * outer opener can recover past unmatched inner openers; unrelated closing
 * tokens are ignored.
 */
internal class BracketStack<T, G> {
    data class Open<T>(
        val expectedCloses: Set<T>,
        val offset: Int,
        val tokenLength: Int,
        val line: Int,
        val depth: Int,
    )

    data class Match<T>(val open: Open<T>)

    private val stacks = HashMap<G, ArrayDeque<Open<T>>>()
    private val expectedCloseCounts = HashMap<G, HashMap<T, Int>>()

    fun open(
        group: G,
        expectedCloses: Set<T>,
        offset: Int,
        tokenLength: Int,
        line: Int,
    ) {
        require(expectedCloses.isNotEmpty())
        val stack = stacks.getOrPut(group) { ArrayDeque() }
        val counts = expectedCloseCounts.getOrPut(group) { HashMap() }
        for (expectedClose in expectedCloses) {
            counts[expectedClose] = (counts[expectedClose] ?: 0) + 1
        }
        stack.addLast(
            Open(
                expectedCloses = expectedCloses,
                offset = offset,
                tokenLength = tokenLength,
                line = line,
                depth = stack.size,
            ),
        )
    }

    fun close(
        group: G,
        token: T,
        checkCanceled: () -> Unit = {},
    ): Match<T>? {
        val stack = stacks[group] ?: return null
        val counts = expectedCloseCounts[group] ?: return null
        if ((counts[token] ?: 0) == 0) return null

        val top = stack.last()
        if (token in top.expectedCloses) {
            stack.removeLast()
            decrement(counts, top.expectedCloses)
            removeGroupIfEmpty(group, stack)
            return Match(top)
        }

        var discarded = 0
        while (stack.isNotEmpty()) {
            if (discarded++ and CANCELLATION_MASK == 0) checkCanceled()
            val open = stack.removeLast()
            decrement(counts, open.expectedCloses)
            if (token in open.expectedCloses) {
                removeGroupIfEmpty(group, stack)
                return Match(open)
            }
        }

        stacks.remove(group)
        expectedCloseCounts.remove(group)
        return null
    }

    private fun decrement(counts: HashMap<T, Int>, tokens: Set<T>) {
        for (token in tokens) {
            val remaining = (counts[token] ?: continue) - 1
            if (remaining == 0) {
                counts.remove(token)
            } else {
                counts[token] = remaining
            }
        }
    }

    private fun removeGroupIfEmpty(group: G, stack: ArrayDeque<Open<T>>) {
        if (stack.isEmpty()) {
            stacks.remove(group)
            expectedCloseCounts.remove(group)
        }
    }

    private companion object {
        const val CANCELLATION_MASK = 0xFF
    }
}
