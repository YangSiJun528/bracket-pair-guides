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

    /** No snapshot is published because completing it would cross [limit]. */
    public class Unavailable(
        override val stamp: AnalysisStamp,
        public val limit: AnalysisLimit,
    ) : AnalysisOutcome
}

/** Product boundary that prevented an authoritative snapshot. */
@ApiStatus.Internal
public enum class AnalysisLimit {
    PAIR_CAPACITY,
    PENDING_OPEN_CAPACITY,
    WORKING_MEMORY,
}
