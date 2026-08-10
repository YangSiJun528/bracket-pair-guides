package com.sijunyang.bracketpairguides.analysis

import org.jetbrains.annotations.ApiStatus

/** Authoritative result of one analysis attempt. */
@ApiStatus.Internal
public sealed interface AnalysisOutcome {
    /** The document identity and policy captured for this attempt. */
    public val stamp: AnalysisStamp

    /** All facets requested by the stamp are available in [snapshot]. */
    public class Complete(
        public val snapshot: BracketSnapshot,
    ) : AnalysisOutcome {
        override val stamp: AnalysisStamp
            get() = snapshot.stamp
    }

    /**
     * [snapshot] contains every requested facet except the one rejected by
     * [limit]. The attempted [stamp] prevents repeated work for the same input.
     */
    public class Limited(
        override val stamp: AnalysisStamp,
        public val snapshot: BracketSnapshot,
        public val limit: AnalysisLimit,
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
    public class Unavailable(
        override val stamp: AnalysisStamp,
        public val limit: AnalysisLimit,
    ) : AnalysisOutcome {
        init {
            require(limit != AnalysisLimit.GUIDE_CAPACITY) {
                "Guide capacity must preserve exact lower facets"
            }
        }
    }
}

/** Product boundary that prevented an authoritative snapshot. */
@ApiStatus.Internal
public enum class AnalysisLimit {
    IDE_CODE_INSIGHT_FILE_SIZE,
    PAIR_CAPACITY,
    PENDING_OPEN_CAPACITY,
    GUIDE_CAPACITY,
}
