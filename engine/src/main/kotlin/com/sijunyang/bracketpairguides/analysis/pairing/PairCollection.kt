package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.pairing.core.PairSink
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

/** A pair table that becomes unavailable instead of exposing a capped prefix. */
internal class PairCollection(
    private val capacity: PairCapacity,
) : PairSink {
    private val draft = PairTable.draft()
    private var pairCount = 0
    private var overflowed = false

    override fun accept(
        openOffset: Int,
        openTokenLength: Int,
        closeOffset: Int,
        closeTokenLength: Int,
        depth: Int,
        openLine: Int,
        closeLine: Int,
    ) {
        if (overflowed) throw PairCapacityReached
        if (pairCount == capacity.maximum) {
            overflowed = true
            throw PairCapacityReached
        }
        draft.accept(
            openOffset,
            openTokenLength,
            closeOffset,
            closeTokenLength,
            depth,
            openLine,
            closeLine,
        )
        pairCount++
    }

    /** Returns null after overflow so the accepted prefix can never be published. */
    fun authoritativePairs(): PairTable? = if (overflowed) null else draft.freeze()
}

/** Allocation-free control signal used only on the first over-capacity pair. */
internal object PairCapacityReached : RuntimeException(null, null, false, false)
