package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisStamp

/** Authoritative result of one analysis attempt. */
internal sealed interface AnalysisOutcome {
    /** The document identity and policy captured for this attempt. */
    val stamp: AnalysisStamp

    /** All facets requested by the stamp are available in [snapshot]. */
    class Complete(
        val snapshot: BracketSnapshot,
    ) : AnalysisOutcome {
        override val stamp: AnalysisStamp
            get() = snapshot.stamp
    }

    /**
     * [snapshot] contains every requested facet except the one rejected by
     * [limit]. The attempted [stamp] prevents repeated work for the same input.
     */
    class Limited(
        override val stamp: AnalysisStamp,
        val snapshot: BracketSnapshot,
        val limit: AnalysisLimit,
    ) : AnalysisOutcome {
        init {
            require(limit == AnalysisLimit.GUIDE_CAPACITY) {
                "Only guide capacity can publish a lower-facet snapshot"
            }
            require(stamp.coverage.guidePosition) {
                "A guide-capacity result must come from a guide request"
            }
            require(
                snapshot.stamp.coverage == stamp.coverage.copy(guidePosition = false) &&
                    stamp.covers(snapshot.stamp),
            ) {
                "A limited snapshot must preserve every requested facet except guides"
            }
        }
    }

    /** No snapshot is published because completing it would cross [limit]. */
    class Unavailable(
        override val stamp: AnalysisStamp,
        val limit: AnalysisLimit,
    ) : AnalysisOutcome {
        init {
            require(limit != AnalysisLimit.GUIDE_CAPACITY) {
                "Guide capacity must preserve exact lower facets"
            }
        }
    }
}

/** Product boundary that prevented an authoritative snapshot. */
internal enum class AnalysisLimit {
    IDE_CODE_INSIGHT_FILE_SIZE,
    PAIR_CAPACITY,
    PENDING_OPEN_CAPACITY,
    GUIDE_CAPACITY,
}
