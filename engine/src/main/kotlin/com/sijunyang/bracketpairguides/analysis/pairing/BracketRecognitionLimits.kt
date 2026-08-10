package com.sijunyang.bracketpairguides.analysis.pairing

/** Product limits for authoritative document-bracket recognition. */
internal object BracketRecognitionLimits {
    // At 100k pairs every PairTable column remains below the common 512 KiB
    // G1 humongous-object boundary. The next geometric growth crosses it.
    val completedPairs: PairCapacity = PairCapacity(100_000)

    // Pending opens are object-backed. A 50k strict-context adversarial scan
    // uses roughly 10-12 MiB on the supported JetBrains Runtime, while still
    // allowing nesting far beyond realistic hand-written source.
    const val pendingOpens: Int = 50_000
}

/** Maximum pair count accepted by one authoritative recognition. */
internal class PairCapacity(val maximum: Int) {
    init {
        require(maximum > 0) { "Pair capacity must be positive" }
    }
}
