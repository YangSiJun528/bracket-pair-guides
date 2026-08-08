package com.sijunyang.bracketpairguides.analysis.index

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.hasWellFormedTokenRange
import org.jetbrains.annotations.ApiStatus

/**
 * Maps a caret offset to the innermost bracket pair that strictly contains it.
 *
 * Build-time events and the active-candidate heap use primitive arrays to avoid
 * allocating two event objects plus priority-queue wrappers for every pair.
 * Caret movement is a binary search over the resulting immutable segments.
 */
@ApiStatus.Internal
public class ActiveBracketPairIndex private constructor(
    private val segmentStarts: IntArray,
    private val segmentPairIndices: IntArray,
) {
    public fun activePairIndex(caretOffset: Int): Int {
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

    public companion object {
        public fun build(
            pairs: List<BracketPair>,
            checkCanceled: () -> Unit = {},
        ): ActiveBracketPairIndex {
            if (pairs.isEmpty()) return EMPTY
            require(pairs.size <= Int.MAX_VALUE / EVENTS_PER_PAIR)

            val candidateStarts = IntArray(pairs.size)
            val candidateEnds = IntArray(pairs.size)
            val events = LongArray(pairs.size * EVENTS_PER_PAIR)
            var eventCount = 0
            for (pairIndex in pairs.indices) {
                if (pairIndex and CANCELLATION_MASK == 0) checkCanceled()
                val pair = pairs[pairIndex]
                if (!pair.hasWellFormedTokenRange()) continue

                val endExclusive = pair.closeOffset + pair.closeTokenLength
                val start = pair.openOffset + 1

                candidateStarts[pairIndex] = start
                candidateEnds[pairIndex] = endExclusive
                events[eventCount++] = encodeEvent(start, pairIndex, isStart = true)
                events[eventCount++] = encodeEvent(endExclusive, pairIndex, isStart = false)
            }
            if (eventCount == 0) return EMPTY

            val sortedEvents = if (eventCount == events.size) events else events.copyOf(eventCount)
            sortedEvents.sortCancellable(checkCanceled)

            val active = BooleanArray(pairs.size)
            val heap = CandidateHeap(candidateStarts, candidateEnds, pairs.size)
            val starts = IntArray(eventCount + 1)
            val winners = IntArray(eventCount + 1)
            var segmentCount = 0
            var eventIndex = 0

            if (eventOffset(sortedEvents[0]) > 0) {
                starts[segmentCount] = 0
                winners[segmentCount++] = NO_PAIR
            }

            while (eventIndex < eventCount) {
                if (eventIndex and CANCELLATION_MASK == 0) checkCanceled()
                val offset = eventOffset(sortedEvents[eventIndex])
                while (eventIndex < eventCount &&
                    eventOffset(sortedEvents[eventIndex]) == offset
                ) {
                    if (eventIndex and CANCELLATION_MASK == 0) checkCanceled()
                    val event = sortedEvents[eventIndex++]
                    val pairIndex = eventPairIndex(event)
                    val startsHere = isStartEvent(event)
                    active[pairIndex] = startsHere
                    if (startsHere) heap.add(pairIndex)
                }
                var removedCandidates = 0
                while (heap.isNotEmpty() && !active[heap.peek()]) {
                    if (removedCandidates++ and CANCELLATION_MASK == 0) checkCanceled()
                    heap.removeTop()
                }

                val winner = if (heap.isNotEmpty()) heap.peek() else NO_PAIR
                if (segmentCount == 0 || winners[segmentCount - 1] != winner) {
                    starts[segmentCount] = offset
                    winners[segmentCount++] = winner
                }
            }
            checkCanceled()

            return ActiveBracketPairIndex(
                // Deeply nested inputs can use every allocated segment. Keep
                // those full arrays instead of copying another 4P integers.
                segmentStarts = starts.copyIfSmaller(segmentCount),
                segmentPairIndices = winners.copyIfSmaller(segmentCount),
            )
        }

        private fun IntArray.copyIfSmaller(size: Int): IntArray =
            if (size == this.size) this else copyOf(size)

        private fun encodeEvent(offset: Int, pairIndex: Int, isStart: Boolean): Long {
            val reference = (pairIndex shl EVENT_KIND_BITS) or
                if (isStart) START_EVENT else END_EVENT
            return (offset.toLong() shl OFFSET_SHIFT) or
                (reference.toLong() and EVENT_REFERENCE_MASK)
        }

        private fun eventOffset(event: Long): Int = (event ushr OFFSET_SHIFT).toInt()

        private fun eventPairIndex(event: Long): Int = event.toInt() ushr EVENT_KIND_BITS

        private fun isStartEvent(event: Long): Boolean {
            return event.toInt() and EVENT_KIND_MASK == START_EVENT
        }

        private val EMPTY = ActiveBracketPairIndex(IntArray(0), IntArray(0))

        public const val NO_PAIR: Int = -1
        private const val EVENTS_PER_PAIR = 2
        private const val EVENT_KIND_BITS = 1
        private const val EVENT_KIND_MASK = 1
        private const val END_EVENT = 0
        private const val START_EVENT = 1
        private const val OFFSET_SHIFT = 32
        private const val EVENT_REFERENCE_MASK = 0xFFFF_FFFFL
        private const val CANCELLATION_MASK = 0xFF
    }

    private class CandidateHeap(
        private val starts: IntArray,
        private val ends: IntArray,
        capacity: Int,
    ) {
        private val values = IntArray(capacity)
        private var size = 0

        fun isNotEmpty(): Boolean = size != 0

        fun peek(): Int = values[0]

        fun add(candidate: Int) {
            var index = size++
            while (index > 0) {
                val parent = (index - 1).ushr(1)
                val parentCandidate = values[parent]
                if (!isPreferred(candidate, parentCandidate)) break
                values[index] = parentCandidate
                index = parent
            }
            values[index] = candidate
        }

        fun removeTop() {
            val replacement = values[--size]
            if (size == 0) return

            var index = 0
            while (true) {
                val left = index * 2 + 1
                if (left >= size) break
                val right = left + 1
                val preferredChild = if (right < size &&
                    isPreferred(values[right], values[left])
                ) {
                    right
                } else {
                    left
                }
                if (!isPreferred(values[preferredChild], replacement)) break
                values[index] = values[preferredChild]
                index = preferredChild
            }
            values[index] = replacement
        }

        private fun isPreferred(first: Int, second: Int): Boolean {
            val firstStart = starts[first]
            val secondStart = starts[second]
            if (firstStart != secondStart) return firstStart > secondStart

            val firstEnd = ends[first]
            val secondEnd = ends[second]
            return if (firstEnd != secondEnd) firstEnd < secondEnd else first < second
        }
    }
}
