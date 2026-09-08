package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.NewUI
import com.intellij.ui.NewUiValue
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import org.assertj.core.api.Assertions.assertThat

class NativeGuideMarkerSourceTest : BasePlatformTestCase() {
    fun testUiPathTracksThePlatformNewUiMode() {
        val original = NewUI.isEnabled()
        try {
            NewUiValue.overrideNewUiForOneRemDevSession(true)
            assertThat(NativeGuideConflictDetector.currentUiPath())
                .isEqualTo(NativeGuideUiPath.NEW_UI)

            NewUiValue.overrideNewUiForOneRemDevSession(false)
            assertThat(NativeGuideConflictDetector.currentUiPath())
                .isEqualTo(NativeGuideUiPath.CLASSIC_UI)
        } finally {
            NewUiValue.overrideNewUiForOneRemDevSession(original)
        }
    }

    fun testNewUiCarrierRequiresEffectiveSettingAndExactDescriptorGeometry() {
        val pair = configureMethodPair()
        val guide = BracketGuide(pair = pair, guideColumn = 4, anchorLine = pair.openLine + 1)
        val editor = myFixture.editor
        val originalIndentGuidesShown = editor.settings.isIndentGuidesShown
        try {
            editor.settings.isIndentGuidesShown = true
            editor.indentsModel.assumeIndents(
                listOf(
                    IndentGuideDescriptor(
                        guide.guideColumn,
                        pair.openLine - 1,
                        pair.openLine,
                        pair.closeLine,
                    ),
                ),
            )

            assertThat(
                NativeGuideConflictDetector.hasVisibleCarrier(
                    editor,
                    guide,
                    NativeGuideUiPath.NEW_UI,
                ),
            ).isTrue()

            editor.indentsModel.assumeIndents(
                listOf(
                    IndentGuideDescriptor(
                        guide.guideColumn,
                        pair.openLine - 1,
                        pair.openLine,
                        pair.closeLine + 1,
                    ),
                ),
            )
            assertThat(
                NativeGuideConflictDetector.hasVisibleCarrier(
                    editor,
                    guide,
                    NativeGuideUiPath.NEW_UI,
                ),
            ).isFalse()

            editor.indentsModel.assumeIndents(
                listOf(
                    IndentGuideDescriptor(
                        guide.guideColumn + 1,
                        pair.openLine - 1,
                        pair.openLine,
                        pair.closeLine,
                    ),
                ),
            )
            assertThat(
                NativeGuideConflictDetector.hasVisibleCarrier(
                    editor,
                    guide,
                    NativeGuideUiPath.NEW_UI,
                ),
            ).isFalse()

            editor.indentsModel.assumeIndents(
                listOf(
                    IndentGuideDescriptor(
                        guide.guideColumn,
                        pair.openLine - 1,
                        pair.openLine,
                        pair.closeLine,
                    ),
                ),
            )
            editor.settings.isIndentGuidesShown = false
            assertThat(
                NativeGuideConflictDetector.hasVisibleCarrier(
                    editor,
                    guide,
                    NativeGuideUiPath.NEW_UI,
                ),
            ).isFalse()
        } finally {
            editor.indentsModel.assumeIndents(emptyList())
            editor.settings.isIndentGuidesShown = originalIndentGuidesShown
        }
    }

    fun testClassicUiCarrierFollowsEffectiveLineMarkerAreaSetting() {
        val pair = configureMethodPair()
        val editor = myFixture.editor
        val guide = BracketGuide(pair = pair, guideColumn = 4, anchorLine = pair.openLine + 1)
        val originalLineMarkerAreaShown = editor.settings.isLineMarkerAreaShown
        try {
            editor.settings.isLineMarkerAreaShown = false
            assertThat(
                NativeGuideConflictDetector.hasVisibleCarrier(
                    editor,
                    guide,
                    NativeGuideUiPath.CLASSIC_UI,
                ),
            ).isFalse()

            editor.settings.isLineMarkerAreaShown = true
            assertThat(
                NativeGuideConflictDetector.hasVisibleCarrier(
                    editor,
                    guide,
                    NativeGuideUiPath.CLASSIC_UI,
                ),
            ).isTrue()
        } finally {
            editor.settings.isLineMarkerAreaShown = originalLineMarkerAreaShown
        }
    }

    fun testBraceBoundaryResolvesDirectPairEvenWhenCurrentScopeIsDisabled() {
        val pair = configureMethodPair()
        myFixture.editor.caretModel.moveToOffset(pair.openOffset + pair.openTokenLength)

        val sources =
            NativeGuideConflictDetector.resolveMarkerSources(
                editor = myFixture.editor,
                pair = pair,
                resolveCurrentScope = false,
            )

        assertThat(sources.direct).isTrue()
        assertThat(sources.currentScope).isFalse()
    }

    fun testScopeFallbackMustBeEnabledAndResolveTheExactActivePair() {
        val pair = configureMethodPair()
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("value") + 2)

        val disabled =
            NativeGuideConflictDetector.resolveMarkerSources(
                editor = myFixture.editor,
                pair = pair,
                resolveCurrentScope = false,
            )
        val enabled =
            NativeGuideConflictDetector.resolveMarkerSources(
                editor = myFixture.editor,
                pair = pair,
                resolveCurrentScope = true,
            )

        assertThat(disabled).isEqualTo(
            NativeGuideConflictDetector.NativeMarkerSources(
                direct = false,
                currentScope = false,
            ),
        )
        assertThat(enabled.direct).isFalse()
        assertThat(enabled.currentScope).isTrue()
    }

    private fun configureMethodPair(): BracketPair {
        val source =
            """
            class Sample {
                void run() {
                    int value = 1;
                }
            }
            """.trimIndent()
        myFixture.configureByText("Sample.java", source)
        val openOffset = source.indexOf('{', source.indexOf("run"))
        val closeOffset = source.indexOf('}', openOffset)
        return BracketPair(
            openOffset = openOffset,
            openTokenLength = 1,
            closeOffset = closeOffset,
            closeTokenLength = 1,
            depth = 1,
            openLine = myFixture.editor.document.getLineNumber(openOffset),
            closeLine = myFixture.editor.document.getLineNumber(closeOffset),
        )
    }
}
