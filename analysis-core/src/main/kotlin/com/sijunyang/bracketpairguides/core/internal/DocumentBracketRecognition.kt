package com.sijunyang.bracketpairguides.core.internal

import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability

/** Authoritative document-pair recognition state. */
internal sealed interface DocumentBracketRecognition {
    class Complete(val pairs: PairTable, val matcherAvailability: BraceMatcherAvailability) :
        DocumentBracketRecognition

    class Unavailable(val refusal: BracketRecognitionRefusal) : DocumentBracketRecognition
}

internal enum class BracketRecognitionRefusal {
    PAIR_CAPACITY,
    PENDING_OPEN_CAPACITY,
}
