package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import com.sijunyang.bracketpairguides.presentation.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import org.assertj.core.api.Assertions.assertThat
import java.awt.Color

internal class GuidePreferenceTransitionsTest : BracketGuideHighlightingFixture() {
    fun testThemeRefreshKeepsExplicitTokenColorsWithoutRebuildingHighlighters() {
        val source = "x { content } y"
        myFixture.configureByText("ThemeTokens.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val options = BracketGuidePreferences(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        BracketGuideSettings.getInstance().replace(options)
        applyPass(
            {
                collections++
                listOf(pair)
            },
        )
        val originalHighlighters = bracketColorHighlighters().toSet()
        val editor = myFixture.editor as EditorEx
        val originalScheme = editor.colorsScheme
        val refreshedColor = Color(0x12, 0x6A, 0xD4)
        val appliedColor = Color(StoredColorFormat.defaultColor(0))
        val refreshedScheme = EditorColorsSchemeImpl(originalScheme).apply {
            setAttributes(
                BracketColorPalette.levelKey(0),
                TextAttributes().apply { foregroundColor = refreshedColor },
            )
        }
        try {
            editor.setColorsScheme(refreshedScheme)
            session().updateOptions(
                options,
                refreshColors = true,
            )

            assertThat(bracketColorHighlighters().toSet()).isEqualTo(originalHighlighters)
            assertThat(bracketColorHighlighters()).allMatch { highlighter ->
                highlighter.getTextAttributes(editor.colorsScheme)?.foregroundColor ==
                    appliedColor
            }
            assertThat(collections).isEqualTo(1)
        } finally {
            editor.setColorsScheme(originalScheme)
        }
    }

    fun testDisabledPassSkipsRecognitionAndReenableCanAnalyze() {
        val source = "x { content } y"
        myFixture.configureByText("Sample.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        BracketGuideSettings.getInstance().replace(BracketGuidePreferences(enabled = false))
        applyPass(pairs)
        assertThat(collections).isEqualTo(0)
        assertThat(ownedHighlighters()).isEmpty()

        val enabled = BracketGuidePreferences()
        BracketGuideSettings.getInstance().replace(enabled)
        session().updateOptions(
            enabled,
            refreshColors = false,
        )
        applyPass(pairs)
        assertThat(collections).isEqualTo(1)
        assertThat(ownedHighlighters()).hasSize(3)
    }

    fun testDisablingAllPairFeaturesReleasesAndRebuildsTheSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("ReleasedSnapshot.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        val enabled = BracketGuideSettings.getInstance().options

        applyPass(pairs)
        val acceptedStamp = stampFor(editor, enabled)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp)).isTrue()
        assertThat(collections).isEqualTo(1)

        applyOptions(enabled.copy(enabled = false))

        assertThat(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp)).isFalse()
        assertThat(ownedHighlighters()).isEmpty()
        applyPass(pairs)
        assertThat(collections).isEqualTo(1)

        applyOptions(enabled)
        applyPass(pairs)

        assertThat(collections).isEqualTo(2)
        assertThat(bracketColorHighlighters()).isNotEmpty()
    }

    fun testLateFullPassCannotRestoreASnapshotAfterAllFeaturesAreDisabled() {
        val source = "x { content } y"
        myFixture.configureByText("LateDisabledSnapshot.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        val enabled = BracketGuideSettings.getInstance().options
        val latePass = testPass(project, editor, pairs)
        inReadAction {
            latePass.doCollectInformation(EmptyProgressIndicator())
        }
        val fullStamp = stampFor(editor, enabled)

        val disabled = enabled.copy(enabled = false)
        applyOptions(disabled)
        latePass.doApplyInformationToEditor()
        val disabledStamp = stampFor(editor, disabled)

        assertThat(EditorGuideSessions.canSkipAnalysis(editor, fullStamp)).isFalse()
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, disabledStamp)).isTrue()
        assertThat(ownedHighlighters()).isEmpty()

        applyOptions(enabled)
        applyPass(pairs)

        assertThat(collections).isEqualTo(2)
        assertThat(bracketColorHighlighters()).isNotEmpty()
    }

    fun testLateFullPassCannotPreventCompactTokenOnlyRebuild() {
        val source = "x { content } y"
        myFixture.configureByText("LateCompactTokenAnalysis.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        val fullOptions = BracketGuideSettings.getInstance().options
        val latePass = testPass(project, editor, pairs)
        inReadAction {
            latePass.doCollectInformation(EmptyProgressIndicator())
        }

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)
        latePass.doApplyInformationToEditor()

        assertThat(collections).isEqualTo(1)
        assertThat(bracketColorHighlighters()).isNotEmpty()
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isFalse()

        applyPass(pairs)

        assertThat(collections).isEqualTo(2)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isTrue()
        assertThat(bracketColorHighlighters()).isNotEmpty()
    }

    fun testLateFullPassCannotReplaceAnAcceptedCompactTokenOnlySnapshot() {
        val pairCount = 30_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("LateFullAfterCompactTokens.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val recognizedPairs = sequentialPairs(pairCount)
        var collections = 0
        var visibleRange = TextRange(0, 256)
        val pairs = {
            collections++
            recognizedPairs
        }
        val fullOptions = BracketGuideSettings.getInstance().options
        val lateFullPass = testPass(
            project = project,
            editor = editor,
            pairs = pairs,
            visibleRange = { visibleRange },
        )
        inReadAction {
            lateFullPass.doCollectInformation(EmptyProgressIndicator())
        }

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        applyPass(pairs) { visibleRange }
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)
        assertThat(collections).isEqualTo(2)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isTrue()

        lateFullPass.doApplyInformationToEditor()

        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isTrue()
        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertThat(collections).isEqualTo(2)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isTrue()
        assertThat(bracketColorHighlighters())
            .describedAs("A late full pass must not discard the compact viewport index")
            .anyMatch {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            }
    }

    fun testReenablingActivePresentationWaitsForTheNextSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("ReenabledSnapshot.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        var collections = 0
        BracketGuideSettings.getInstance().replace(BracketGuidePreferences(enabled = false))
        applyPass(
            testPass(
                project = project,
                editor = editor,
                pairs = {
                    collections++
                    listOf(pair)
                },
            ),
        )
        assertThat(collections).isEqualTo(0)
        assertThat(activeGuide()).isNull()

        val enabled = BracketGuidePreferences()
        BracketGuideSettings.getInstance().replace(enabled)
        session().updateOptions(
            enabled,
            refreshColors = false,
        )

        assertThat(collections).isEqualTo(0)
        assertThat(activeGuide()).isNull()

        applyPass(testPass(project, editor, pairs = { listOf(pair) }))
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(pair)
    }

    fun testLanguageSelectionInvalidatesPresentationUntilTheNextSnapshot() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("LanguageSelection.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call") + 2)
        val capabilityId = checkNotNull(
            BraceLanguageCatalog().installedFamilies()
                .firstOrNull { family ->
                    family.id == myFixture.file.language.id ||
                        myFixture.file.language.displayName in family.memberDisplayNames
                }
                ?.id,
        )

        applyPass()
        assertThat(bracketColorHighlighters()).isNotEmpty()
        assertThat(activeGuide()).isNotNull()

        val disabled = BracketGuideSettings.getInstance().options.copy(
            disabledLanguageIds = setOf(capabilityId),
        )
        applyOptions(disabled)
        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(activeGuide()).isNull()

        applyPass()
        assertThat(ownedHighlighters()).isEmpty()

        val enabled = disabled.copy(disabledLanguageIds = emptySet())
        applyOptions(enabled)
        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(activeGuide()).isNull()

        applyPass()
        assertThat(bracketColorHighlighters()).isNotEmpty()
        assertThat(activeGuide()).isNotNull()
    }
}
