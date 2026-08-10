package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairSink
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pipeline.PairCapacity

/** A pair table that becomes unavailable instead of exposing a capped prefix. */
internal class PairCollection(
    private val capacity: PairCapacity,
) : PairSink {
    private val draft = PairTable.draft()
    private var pairCount = 0

    var limit: AnalysisLimit? = null
        private set

    override fun accept(
        openOffset: Int,
        openTokenLength: Int,
        closeOffset: Int,
        closeTokenLength: Int,
        depth: Int,
        openLine: Int,
        closeLine: Int,
    ) {
        if (limit != null) throw PairCapacityReached
        if (pairCount == capacity.maximumPairCount) {
            limit = AnalysisLimit.PAIR_CAPACITY
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
    fun complete(): PairTable? = if (limit == null) draft.freeze() else null
}

/** Allocation-free control signal used only on the first over-capacity pair. */
internal object PairCapacityReached : RuntimeException(null, null, false, false)
