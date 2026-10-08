package com.sijunyang.bracketpairguides.ui.work

import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair

/** The editor owns this request contract; implementations own all computation and jobs. */
interface GuideWorkFactory {
    /** EDT, exactly once for one view lifetime. */
    fun attach(editor: Editor, view: GuideView): GuideWork
}

interface GuideWork : AutoCloseable {
    /** Installs complete desired state and revokes obsolete work before scheduling replacements. */
    fun reconcile(demand: GuideDemand)

    /** Independent, thread-safe daemon wake-up. Never inherits the caller's read action. */
    fun refresh()

    /** Cheap paint evidence. Native input inspection must happen after the paint callback. */
    fun observeNativeGuide(candidate: DisplayedGuide)

    /** Immediately revokes publication; safe and idempotent from any thread. */
    override fun close()
}

interface GuideView {
    /** EDT, non-suspending. May return obsolete after a synchronous SDK listener reenters. */
    fun applyAnalysis(update: AnalysisUpdate): ViewApplication

    fun applyRepair(update: RepairUpdate): ViewApplication

    fun reportNativeConflict(evidence: NativeConflictEvidence)
}

enum class GuideChange { CONTENT, CONFIGURATION, PRESENTATION }
enum class ViewApplication { APPLIED, OBSOLETE }

/** A frozen value request; mutable caller collections cannot change an installed demand. */
class GuideDemand(
    val revision: Long,
    val coverage: AnalysisCoverage,
    disabledLanguageIds: Set<String>,
    val visible: Boolean,
    val guideRevision: Long,
    val repair: RepairIntent?,
    val change: GuideChange,
    val nativeInterest: NativeInterest,
) {
    val disabledLanguageIds: Set<String> = java.util.Collections.unmodifiableSet(HashSet(disabledLanguageIds))

    fun copy(
        revision: Long = this.revision,
        coverage: AnalysisCoverage = this.coverage,
        disabledLanguageIds: Set<String> = this.disabledLanguageIds,
        visible: Boolean = this.visible,
        guideRevision: Long = this.guideRevision,
        repair: RepairIntent? = this.repair,
        change: GuideChange = this.change,
        nativeInterest: NativeInterest = this.nativeInterest,
    ): GuideDemand = GuideDemand(revision, coverage, disabledLanguageIds, visible, guideRevision, repair, change, nativeInterest)

    override fun equals(other: Any?): Boolean = this === other || other is GuideDemand &&
        revision == other.revision && coverage == other.coverage && disabledLanguageIds == other.disabledLanguageIds &&
        visible == other.visible && guideRevision == other.guideRevision && repair == other.repair &&
        change == other.change && nativeInterest == other.nativeInterest

    override fun hashCode(): Int = java.util.Objects.hash(revision, coverage, disabledLanguageIds, visible,
        guideRevision, repair, change, nativeInterest)
}

data class RepairIntent(val pair: BracketPair, val exact: Boolean, val previousAnchor: Int?)
data class NativeInterest(val episode: Long, val enabled: Boolean)
data class DisplayedGuide(val revision: Long, val guide: BracketGuide)
data class AnalysisUpdate(val demandRevision: Long, val result: AnalysisResult)
data class RepairUpdate(val guideRevision: Long, val guide: BracketGuide)
data class NativeConflictEvidence(val guideRevision: Long, val episode: Long, val guide: BracketGuide)
