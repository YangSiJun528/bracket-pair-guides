package com.sijunyang.bracketpairguides.runtime.nativeproof

import com.sijunyang.bracketpairguides.ui.work.DisplayedGuide
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.NativeInterest

/** One editor's proof authority. Equal geometry never revives an earlier paint observation. */
internal class NativeEvidenceGate {
    @Volatile private var current: Proof? = null

    @Synchronized fun invalidate() {
        current = null
    }

    @Synchronized fun admit(candidate: DisplayedGuide, caret: Int, sourceEpoch: Long, demand: GuideDemand): Proof? {
        if (!demand.nativeInterest.enabled || candidate.revision != demand.guideRevision || !demand.visible) return null
        val previous = current
        if (previous != null && previous.candidate == candidate && previous.caret == caret &&
            previous.sourceEpoch == sourceEpoch && previous.interest == demand.nativeInterest
        ) {
            return null
        }
        return Proof(candidate, caret, sourceEpoch, demand.nativeInterest).also { current = it }
    }

    fun isCurrent(proof: Proof): Boolean = current === proof

    fun isCurrent(proof: Proof, demand: GuideDemand, caret: Int, sourceEpoch: Long): Boolean =
        isCurrent(proof) && demand.visible && demand.guideRevision == proof.candidate.revision &&
            demand.nativeInterest == proof.interest && proof.interest.enabled && proof.caret == caret &&
            proof.sourceEpoch == sourceEpoch

    class Proof internal constructor(
        val candidate: DisplayedGuide,
        val caret: Int,
        val sourceEpoch: Long,
        val interest: NativeInterest,
    )
}
