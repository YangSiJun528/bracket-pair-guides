package com.sijunyang.bracketpairguides.analysis.pipeline

import com.sijunyang.bracketpairguides.analysis.AnalysisLimit

/** Product policy for bounded analysis state and primitive-array workspaces. */
internal object AnalysisBudget {
    private const val MAXIMUM_PAIR_COUNT = 200_000
    private const val MAXIMUM_PENDING_OPEN_COUNT = 200_000
    // Total concurrently live primitive payload for one analysis. The guide
    // index keeps its independent 16 MiB shape cap; together the current
    // richest 200k-pair layout remains below this 48 MiB analysis ceiling.
    private const val MAXIMUM_WORKING_BYTES = 48L * 1024 * 1024

    val pairCapacity: PairCapacity = PairCapacity(MAXIMUM_PAIR_COUNT)
    const val maximumPendingOpenCount: Int = MAXIMUM_PENDING_OPEN_COUNT

    /**
     * Returns the first product boundary crossed by [pairCount] and [layout].
     *
     * The estimate models the largest simultaneously live primitive payload,
     * including the geometric spare capacity of [PairTable][com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable],
     * cancellable sort workspaces, retained indexes, and an optional guide index.
     */
    fun limitAt(
        pairCount: Int,
        layout: IndexLayout,
        guidePayloadBytes: Long,
    ): AnalysisLimit? {
        require(pairCount >= 0) { "Pair count must not be negative" }
        require(guidePayloadBytes >= 0L) { "Guide payload must not be negative" }
        if (pairCount > pairCapacity.maximumPairCount) {
            return AnalysisLimit.PAIR_CAPACITY
        }
        return if (workingBytes(pairCount, layout, guidePayloadBytes) > MAXIMUM_WORKING_BYTES) {
            AnalysisLimit.WORKING_MEMORY
        } else {
            null
        }
    }

    private fun workingBytes(
        pairCount: Int,
        layout: IndexLayout,
        guidePayloadBytes: Long,
    ): Long {
        val pairBytes = pairCount.toLong()
        val indexBuildBytes = saturatedAdd(
            FIXED_BYTES,
            saturatedProduct(pairBytes, layout.buildBytesPerPair()),
        )
        val completedGuideBytes = saturatedAdd(
            saturatedAdd(
                FIXED_BYTES,
                saturatedProduct(pairBytes, layout.retainedBytesPerPair()),
            ),
            guidePayloadBytes,
        )
        return maxOf(indexBuildBytes, completedGuideBytes)
    }

    private fun saturatedProduct(first: Long, second: Long): Long =
        if (first == 0L || second == 0L) {
            0L
        } else if (first > Long.MAX_VALUE / second) {
            Long.MAX_VALUE
        } else {
            first * second
        }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    private fun IndexLayout.buildBytesPerPair(): Long = when {
        activePair && tokenStorage == TokenStorage.ATTACHED -> {
            // Pair table + retained active index + token build and sort workspace.
            PAIR_TABLE_BYTES_PER_PAIR + ACTIVE_RETAINED_BYTES_PER_PAIR +
                TOKEN_BUILD_BYTES_PER_PAIR
        }
        activePair -> {
            // Pair table + the active-index build peak.
            PAIR_TABLE_BYTES_PER_PAIR + ACTIVE_BUILD_BYTES_PER_PAIR
        }
        tokenStorage == TokenStorage.DETACHED -> {
            // Pair table + detached-token build and sort workspace.
            PAIR_TABLE_BYTES_PER_PAIR + TOKEN_BUILD_BYTES_PER_PAIR
        }
        tokenStorage == TokenStorage.ATTACHED -> {
            // Defensive support for a future layout that retains the pair table.
            PAIR_TABLE_BYTES_PER_PAIR + TOKEN_BUILD_BYTES_PER_PAIR
        }
        else -> PAIR_TABLE_BYTES_PER_PAIR
    }

    private fun IndexLayout.retainedBytesPerPair(): Long = when {
        activePair && tokenStorage == TokenStorage.ATTACHED ->
            PAIR_TABLE_BYTES_PER_PAIR + ACTIVE_RETAINED_BYTES_PER_PAIR +
                TOKEN_RETAINED_BYTES_PER_PAIR
        activePair -> PAIR_TABLE_BYTES_PER_PAIR + ACTIVE_RETAINED_BYTES_PER_PAIR
        tokenStorage == TokenStorage.DETACHED -> TOKEN_DETACHED_BYTES_PER_PAIR
        tokenStorage == TokenStorage.ATTACHED ->
            PAIR_TABLE_BYTES_PER_PAIR + TOKEN_RETAINED_BYTES_PER_PAIR
        else -> 0L
    }

    // Seven IntArrays with the draft's 1.5x geometric capacity.
    private const val PAIR_TABLE_BYTES_PER_PAIR = 42L
    // Candidate geometry, events, sort workspace, activity flags, heap, and output segments.
    private const val ACTIVE_BUILD_BYTES_PER_PAIR = 45L
    private const val ACTIVE_RETAINED_BYTES_PER_PAIR = 16L
    // Encoded tokens plus the cancellable merge workspace.
    private const val TOKEN_BUILD_BYTES_PER_PAIR = 32L
    private const val TOKEN_RETAINED_BYTES_PER_PAIR = 16L
    // Encoded tokens, packed token lengths, and detached depths.
    private const val TOKEN_DETACHED_BYTES_PER_PAIR = 28L
    private const val FIXED_BYTES = 1_024L
}

/** Maximum authoritative pair count accepted by the product policy. */
internal class PairCapacity(val maximumPairCount: Int) {
    init {
        require(maximumPairCount > 0) { "Pair capacity must be positive" }
    }
}
