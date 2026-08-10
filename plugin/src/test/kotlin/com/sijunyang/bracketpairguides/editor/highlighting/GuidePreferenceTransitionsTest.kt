package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.presentation.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.awt.Color

internal class GuidePreferenceTransitionsTest : BracketGuideHighlightingFixture() {
    fun testThemeRefreshUpdatesTokenColorsWithoutRebuildingHighlighters() {
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

            assertEquals(originalHighlighters, bracketColorHighlighters().toSet())
            assertTrue(
                bracketColorHighlighters().all { highlighter ->
                    highlighter.getTextAttributes(editor.colorsScheme)?.foregroundColor ==
                        refreshedColor
                },
            )
            assertEquals(1, collections)
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
        assertEquals(0, collections)
        assertTrue(ownedHighlighters().isEmpty())

        val enabled = BracketGuidePreferences()
        BracketGuideSettings.getInstance().replace(enabled)
        session().updateOptions(
            enabled,
            refreshColors = false,
        )
        applyPass(pairs)
        assertEquals(1, collections)
        assertEquals(3, ownedHighlighters().size)
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
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp))
        assertEquals(1, collections)

        applyOptions(enabled.copy(enabled = false))

        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp))
        assertTrue(ownedHighlighters().isEmpty())
        applyPass(pairs)
        assertEquals(1, collections)

        applyOptions(enabled)
        applyPass(pairs)

        assertEquals(2, collections)
        assertTrue(bracketColorHighlighters().isNotEmpty())
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

        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, fullStamp))
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, disabledStamp))
        assertTrue(ownedHighlighters().isEmpty())

        applyOptions(enabled)
        applyPass(pairs)

        assertEquals(2, collections)
        assertTrue(bracketColorHighlighters().isNotEmpty())
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

        assertEquals(1, collections)
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))

        applyPass(pairs)

        assertEquals(2, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        assertTrue(bracketColorHighlighters().isNotEmpty())
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
        assertEquals(2, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))

        lateFullPass.doApplyInformationToEditor()

        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertEquals(2, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        assertTrue(
            "A late full pass must not discard the compact viewport index",
            bracketColorHighlighters().any {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            },
        )
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
        assertEquals(0, collections)
        assertNull(activeGuide())

        val enabled = BracketGuidePreferences()
        BracketGuideSettings.getInstance().replace(enabled)
        session().updateOptions(
            enabled,
            refreshColors = false,
        )

        assertEquals(0, collections)
        assertNull(activeGuide())

        applyPass(testPass(project, editor, pairs = { listOf(pair) }))
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testLanguageSelectionInvalidatesPresentationUntilTheNextSnapshot() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("LanguageSelection.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call") + 2)
        val capabilityId = checkNotNull(
            service<BracketAnalysis>().installedLanguages()
                .firstOrNull { family ->
                    family.id == myFixture.file.language.id ||
                        myFixture.file.language.displayName in family.memberDisplayNames
                }
                ?.id,
        )

        applyPass()
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertNotNull(activeGuide())

        val disabled = BracketGuideSettings.getInstance().options.copy(
            disabledLanguageIds = setOf(capabilityId),
        )
        applyOptions(disabled)
        assertTrue(bracketColorHighlighters().isEmpty())
        assertNull(activeGuide())

        applyPass()
        assertTrue(ownedHighlighters().isEmpty())

        val enabled = disabled.copy(disabledLanguageIds = emptySet())
        applyOptions(enabled)
        assertTrue(bracketColorHighlighters().isEmpty())
        assertNull(activeGuide())

        applyPass()
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertNotNull(activeGuide())
    }
}
