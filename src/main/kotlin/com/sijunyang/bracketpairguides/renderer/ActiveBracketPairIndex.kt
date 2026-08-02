package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import java.util.PriorityQueue

/**
 * Maps a caret offset to the innermost bracket pair that strictly contains it.
 *
 * The index is built once after bracket recognition. Caret movement then needs
 * only a binary search instead of rescanning every pair.
 */
internal class ActiveBracketPairIndex private constructor(
    private val segmentStarts: IntArray,
    private val segmentPairIndices: IntArray,
) {
    internal fun activePairIndex(caretOffset: Int): Int {
        if (caretOffset < 0 || segmentStarts.isEmpty()) return NO_PAIR

        var low = 0
        var high = segmentStarts.lastIndex
        var segment = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (segmentStarts[middle] <= caretOffset) {
                segment = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return if (segment >= 0) segmentPairIndices[segment] else NO_PAIR
    }

    companion object {
        fun build(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit = {},
        ): ActiveBracketPairIndex {
            if (pairs.isEmpty()) return EMPTY

            val candidates = arrayOfNulls<Candidate>(pairs.size)
            val events = ArrayList<Event>(pairs.size * 2)
            pairs.forEachIndexed { index, pair ->
                if (index and CANCELLATION_MASK == 0) checkCanceled()

                val closeEnd = (pair.closeOffset.toLong() + pair.closeTokenLength)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                val activeStart = (pair.openOffset.toLong() + 1)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                if (pair.openOffset < 0 || activeStart >= closeEnd) return@forEachIndexed

                val candidate = Candidate(
                    pairIndex = index,
                    start = activeStart,
                    endExclusive = closeEnd,
                )
                candidates[index] = candidate
                events += Event(candidate.start, index, isStart = true)
                events += Event(candidate.endExclusive, index, isStart = false)
            }
            if (events.isEmpty()) return EMPTY

            events.sortWith(
                compareBy(Event::offset)
                    .thenBy { if (it.isStart) 1 else 0 },
            )
            checkCanceled()

            val active = BooleanArray(pairs.size)
            val queue = PriorityQueue(CANDIDATE_ORDER)
            val starts = ArrayList<Int>()
            val winners = ArrayList<Int>()
            var eventIndex = 0

            if (events.first().offset > 0) {
                starts += 0
                winners += NO_PAIR
            }

            while (eventIndex < events.size) {
                if (eventIndex and CANCELLATION_MASK == 0) checkCanceled()
                val offset = events[eventIndex].offset
                while (eventIndex < events.size && events[eventIndex].offset == offset) {
                    val event = events[eventIndex++]
                    active[event.pairIndex] = event.isStart
                    if (event.isStart) {
                        candidates[event.pairIndex]?.let(queue::add)
                    }
                }
                while (queue.isNotEmpty() && !active[queue.peek().pairIndex]) {
                    queue.remove()
                }

                val winner = queue.peek()?.pairIndex ?: NO_PAIR
                if (winners.lastOrNull() != winner) {
                    starts += offset
                    winners += winner
                }
            }
            checkCanceled()

            return ActiveBracketPairIndex(
                segmentStarts = starts.toIntArray(),
                segmentPairIndices = winners.toIntArray(),
            )
        }

        private val EMPTY = ActiveBracketPairIndex(
            segmentStarts = IntArray(0),
            segmentPairIndices = IntArray(0),
        )

        private val CANDIDATE_ORDER = Comparator<Candidate> { first, second ->
            compareValues(second.start, first.start)
                .takeUnless { it == 0 }
                ?: compareValues(first.endExclusive, second.endExclusive)
                    .takeUnless { it == 0 }
                ?: compareValues(first.pairIndex, second.pairIndex)
        }

        internal const val NO_PAIR = -1
        private const val CANCELLATION_MASK = 0xFF
    }

    private data class Candidate(
        val pairIndex: Int,
        val start: Int,
        val endExclusive: Int,
    )

    private data class Event(
        val offset: Int,
        val pairIndex: Int,
        val isStart: Boolean,
    )
}
