package com.sijunyang.bracketpairguides.analysis.pipeline

/** Product policy for bounded bracket recognition state. */
internal object AnalysisBudget {
    // At 100k pairs every PairTable column remains below the common 512 KiB
    // G1 humongous-object boundary. The next geometric growth crosses it.
    private const val MAXIMUM_PAIR_COUNT = 100_000

    // Pending opens are object-backed. A 50k strict-context adversarial scan
    // uses roughly 10-12 MiB on the supported JetBrains Runtime, while still
    // allowing nesting far beyond realistic hand-written source.
    private const val MAXIMUM_PENDING_OPEN_COUNT = 50_000

    val pairCapacity: PairCapacity = PairCapacity(MAXIMUM_PAIR_COUNT)
    const val maximumPendingOpenCount: Int = MAXIMUM_PENDING_OPEN_COUNT
}

/** Maximum authoritative pair count accepted by the product policy. */
internal class PairCapacity(val maximumPairCount: Int) {
    init {
        require(maximumPairCount > 0) { "Pair capacity must be positive" }
    }
}
