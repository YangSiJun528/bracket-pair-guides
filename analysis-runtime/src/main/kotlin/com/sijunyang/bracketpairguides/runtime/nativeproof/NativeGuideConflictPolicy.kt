package com.sijunyang.bracketpairguides.runtime.nativeproof

import com.sijunyang.bracketpairguides.model.BracketGuide

/** Supported native rendering paths for a standard monolithic editor. */
enum class NativeGuideUiPath {
    NEW_UI,
    CLASSIC_UI,
    UNCLASSIFIED,
}

/** Public indent-model facts needed to match a native carrier to plugin geometry. */
data class NativeIndentGuideGeometry(val indentLevel: Int, val startLine: Int, val endLine: Int) {
    fun matches(guide: BracketGuide): Boolean = indentLevel == guide.guideColumn &&
        startLine == guide.pair.openLine &&
        endLine == guide.pair.closeLine
}

/** Pure conflict inputs kept separate from IntelliJ renderer implementation details. */
data class NativeGuideConflictFacts(
    val pluginEnabledForEditor: Boolean,
    val activeGuideEnabled: Boolean,
    val verticalGuideEnabled: Boolean,
    val displayedMultilineVerticalGuide: BracketGuide?,
    val matchedBraceHighlightingEnabled: Boolean,
    val currentScopeHighlightingEnabled: Boolean,
    val directMatchedBraceResolvesPair: Boolean,
    val currentScopeResolvesPair: Boolean,
    val uiPath: NativeGuideUiPath,
    val effectiveIndentGuidesShown: Boolean,
    val lineMarkerAreaShown: Boolean,
    val matchingIndentGuide: NativeIndentGuideGeometry?,
    val supportedEditorPath: Boolean,
)

/** Classifies native highlight conflicts from immutable facts, without platform access. */
object NativeGuideConflictPolicy {
    fun isConflict(facts: NativeGuideConflictFacts): Boolean {
        val guide = facts.displayedMultilineVerticalGuide ?: return false
        if (!facts.supportedEditorPath ||
            !facts.pluginEnabledForEditor ||
            !facts.activeGuideEnabled ||
            !facts.verticalGuideEnabled ||
            guide.pair.openLine >= guide.pair.closeLine ||
            !facts.matchedBraceHighlightingEnabled
        ) {
            return false
        }

        if (
            !hasVisibleNativeCarrier(
                uiPath = facts.uiPath,
                effectiveIndentGuidesShown = facts.effectiveIndentGuidesShown,
                lineMarkerAreaShown = facts.lineMarkerAreaShown,
                matchingIndentGuide = facts.matchingIndentGuide,
                guide = guide,
            )
        ) {
            return false
        }

        val directMatchedBraceCapability = facts.directMatchedBraceResolvesPair
        val currentScopeCapability =
            facts.currentScopeHighlightingEnabled &&
                facts.currentScopeResolvesPair
        return directMatchedBraceCapability || currentScopeCapability
    }

    fun hasVisibleNativeCarrier(
        uiPath: NativeGuideUiPath,
        effectiveIndentGuidesShown: Boolean,
        lineMarkerAreaShown: Boolean,
        matchingIndentGuide: NativeIndentGuideGeometry?,
        guide: BracketGuide,
    ): Boolean = when (uiPath) {
        NativeGuideUiPath.NEW_UI ->
            effectiveIndentGuidesShown &&
                matchingIndentGuide?.matches(guide) == true

        NativeGuideUiPath.CLASSIC_UI -> lineMarkerAreaShown

        NativeGuideUiPath.UNCLASSIFIED -> false
    }
}
