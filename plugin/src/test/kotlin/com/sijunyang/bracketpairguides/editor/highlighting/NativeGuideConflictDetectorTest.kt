package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class NativeGuideConflictDetectorTest {
    @Test
    fun `new UI requires effective indent guides and a matching native descriptor`() {
        assertThat(conflicts(baseFacts(uiPath = NativeGuideUiPath.NEW_UI))).isTrue()

        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.NEW_UI,
                    effectiveIndentGuidesShown = false,
                ),
            ),
        ).isFalse()
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.NEW_UI,
                    matchingIndentGuide = null,
                ),
            ),
        ).isFalse()
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.NEW_UI,
                    matchingIndentGuide = MATCHING_GEOMETRY.copy(indentLevel = GUIDE.guideColumn + 1),
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `native geometry must match the plugin column and exact pair lines`() {
        assertThat(MATCHING_GEOMETRY.matches(GUIDE)).isTrue()
        assertThat(MATCHING_GEOMETRY.copy(startLine = OPEN_LINE - 1).matches(GUIDE)).isFalse()
        assertThat(MATCHING_GEOMETRY.copy(startLine = OPEN_LINE + 1).matches(GUIDE)).isFalse()
        assertThat(MATCHING_GEOMETRY.copy(endLine = CLOSE_LINE + 1).matches(GUIDE)).isFalse()
        assertThat(MATCHING_GEOMETRY.copy(endLine = CLOSE_LINE - 1).matches(GUIDE)).isFalse()
    }

    @Test
    fun `classic UI marker remains a conflict when regular indent guides are hidden`() {
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.CLASSIC_UI,
                    effectiveIndentGuidesShown = false,
                    matchingIndentGuide = null,
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `classic UI requires a visible line marker area`() {
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.CLASSIC_UI,
                    lineMarkerAreaShown = false,
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `unclassified renderer path is skipped conservatively`() {
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.UNCLASSIFIED,
                    effectiveIndentGuidesShown = true,
                    matchingIndentGuide = MATCHING_GEOMETRY,
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `native carrier preflight rejects paths that cannot paint the guide`() {
        assertThat(
            NativeGuideConflictDetector.hasVisibleNativeCarrier(
                uiPath = NativeGuideUiPath.NEW_UI,
                effectiveIndentGuidesShown = true,
                lineMarkerAreaShown = true,
                matchingIndentGuide = MATCHING_GEOMETRY,
                guide = GUIDE,
            ),
        ).isTrue()
        assertThat(
            NativeGuideConflictDetector.hasVisibleNativeCarrier(
                uiPath = NativeGuideUiPath.NEW_UI,
                effectiveIndentGuidesShown = false,
                lineMarkerAreaShown = true,
                matchingIndentGuide = MATCHING_GEOMETRY,
                guide = GUIDE,
            ),
        ).isFalse()
        assertThat(
            NativeGuideConflictDetector.hasVisibleNativeCarrier(
                uiPath = NativeGuideUiPath.CLASSIC_UI,
                effectiveIndentGuidesShown = true,
                lineMarkerAreaShown = false,
                matchingIndentGuide = MATCHING_GEOMETRY,
                guide = GUIDE,
            ),
        ).isFalse()
        assertThat(
            NativeGuideConflictDetector.hasVisibleNativeCarrier(
                uiPath = NativeGuideUiPath.UNCLASSIFIED,
                effectiveIndentGuidesShown = true,
                lineMarkerAreaShown = true,
                matchingIndentGuide = MATCHING_GEOMETRY,
                guide = GUIDE,
            ),
        ).isFalse()
    }

    @Test
    fun `regular indent guide alone never triggers the advisory`() {
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.NEW_UI,
                    matchedBraceHighlightingEnabled = false,
                    currentScopeHighlightingEnabled = false,
                    effectiveIndentGuidesShown = true,
                    matchingIndentGuide = MATCHING_GEOMETRY,
                ),
            ),
        ).isFalse()
        assertThat(
            conflicts(
                baseFacts(
                    uiPath = NativeGuideUiPath.CLASSIC_UI,
                    matchedBraceHighlightingEnabled = false,
                    currentScopeHighlightingEnabled = false,
                    effectiveIndentGuidesShown = true,
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `direct marker must resolve the displayed active pair`() {
        assertThat(
            conflicts(
                baseFacts(
                    currentScopeHighlightingEnabled = false,
                    directMatchedBraceResolvesPair = true,
                ),
            ),
        ).isTrue()
        assertThat(
            conflicts(
                baseFacts(
                    currentScopeHighlightingEnabled = false,
                    directMatchedBraceResolvesPair = false,
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `current scope must resolve the displayed active pair`() {
        assertThat(
            conflicts(
                baseFacts(
                    directMatchedBraceResolvesPair = false,
                    currentScopeHighlightingEnabled = true,
                    currentScopeResolvesPair = true,
                ),
            ),
        ).isTrue()
        assertThat(
            conflicts(
                baseFacts(
                    directMatchedBraceResolvesPair = false,
                    currentScopeHighlightingEnabled = false,
                    currentScopeResolvesPair = true,
                ),
            ),
        ).isFalse()
        assertThat(
            conflicts(
                baseFacts(
                    directMatchedBraceResolvesPair = false,
                    currentScopeHighlightingEnabled = true,
                    currentScopeResolvesPair = false,
                ),
            ),
        ).isFalse()
        assertThat(
            conflicts(
                baseFacts(
                    matchedBraceHighlightingEnabled = false,
                    directMatchedBraceResolvesPair = false,
                    currentScopeHighlightingEnabled = true,
                    currentScopeResolvesPair = true,
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `all plugin and editor gates must be open`() {
        val closedGates =
            listOf(
                baseFacts(pluginEnabledForEditor = false),
                baseFacts(activeGuideEnabled = false),
                baseFacts(verticalGuideEnabled = false),
                baseFacts(supportedEditorPath = false),
            )

        assertThat(closedGates).allMatch { facts -> !conflicts(facts) }
    }

    @Test
    fun `only an actually displayed multiline vertical guide is eligible`() {
        assertThat(conflicts(baseFacts(displayedMultilineVerticalGuide = GUIDE))).isTrue()
        assertThat(conflicts(baseFacts(displayedMultilineVerticalGuide = null))).isFalse()

        val singleLine =
            GUIDE.copy(
                pair = GUIDE.pair.copy(closeLine = GUIDE.pair.openLine),
            )
        assertThat(conflicts(baseFacts(displayedMultilineVerticalGuide = singleLine))).isFalse()
    }

    private fun conflicts(facts: NativeGuideConflictFacts): Boolean = NativeGuideConflictDetector.isConflict(facts)

    private fun baseFacts(
        pluginEnabledForEditor: Boolean = true,
        activeGuideEnabled: Boolean = true,
        verticalGuideEnabled: Boolean = true,
        displayedMultilineVerticalGuide: BracketGuide? = GUIDE,
        matchedBraceHighlightingEnabled: Boolean = true,
        currentScopeHighlightingEnabled: Boolean = false,
        directMatchedBraceResolvesPair: Boolean = true,
        currentScopeResolvesPair: Boolean = false,
        uiPath: NativeGuideUiPath = NativeGuideUiPath.CLASSIC_UI,
        effectiveIndentGuidesShown: Boolean = true,
        lineMarkerAreaShown: Boolean = true,
        matchingIndentGuide: NativeIndentGuideGeometry? = MATCHING_GEOMETRY,
        supportedEditorPath: Boolean = true,
    ): NativeGuideConflictFacts = NativeGuideConflictFacts(
        pluginEnabledForEditor = pluginEnabledForEditor,
        activeGuideEnabled = activeGuideEnabled,
        verticalGuideEnabled = verticalGuideEnabled,
        displayedMultilineVerticalGuide = displayedMultilineVerticalGuide,
        matchedBraceHighlightingEnabled = matchedBraceHighlightingEnabled,
        currentScopeHighlightingEnabled = currentScopeHighlightingEnabled,
        directMatchedBraceResolvesPair = directMatchedBraceResolvesPair,
        currentScopeResolvesPair = currentScopeResolvesPair,
        uiPath = uiPath,
        effectiveIndentGuidesShown = effectiveIndentGuidesShown,
        lineMarkerAreaShown = lineMarkerAreaShown,
        matchingIndentGuide = matchingIndentGuide,
        supportedEditorPath = supportedEditorPath,
    )

    private companion object {
        const val OPEN_OFFSET = 4
        const val CLOSE_OFFSET = 40
        const val OPEN_LINE = 1
        const val CLOSE_LINE = 4

        val GUIDE =
            BracketGuide(
                pair =
                BracketPair(
                    openOffset = OPEN_OFFSET,
                    openTokenLength = 1,
                    closeOffset = CLOSE_OFFSET,
                    closeTokenLength = 1,
                    depth = 0,
                    openLine = OPEN_LINE,
                    closeLine = CLOSE_LINE,
                ),
                guideColumn = 4,
                anchorLine = 2,
            )

        val MATCHING_GEOMETRY =
            NativeIndentGuideGeometry(
                indentLevel = GUIDE.guideColumn,
                startLine = OPEN_LINE,
                endLine = CLOSE_LINE,
            )
    }
}
