package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.presentation.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.awt.Color

internal class GuidePresentationTest : BracketGuideHighlightingFixture() {
    fun testCaretMovementReplacesOnlyActivePresentationUsingCachedRecognition() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("Sample.txt", source)
        val outer = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val inner = BracketPair(
            openOffset = source.indexOf('('),
            openTokenLength = 1,
            closeOffset = source.indexOf(')'),
            closeTokenLength = 1,
            depth = 1,
            openLine = 0,
            closeLine = 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(outer, inner)
        }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("inner"))

        applyPass(pairs)
        val innerGuide = checkNotNull(activeGuide())
        assertEquals(0, innerGuide.startOffset)
        assertEquals(editor.document.textLength, innerGuide.endOffset)
        val innerPairHighlights = activePairHighlighters().toSet()
        assertTrue(innerPairHighlights.isEmpty())
        val originalBrackets = bracketColorHighlighters().toSet()
        assertEquals(1, collections)
        assertEquals(inner, activeGuideState()?.guide?.pair)
        assertEquals(4, bracketColorHighlighters().size)

        editor.caretModel.moveToOffset(source.indexOf("inner") + 1)
        assertEquals(1, collections)
        assertEquals(innerGuide, activeGuide())
        assertEquals(innerPairHighlights, activePairHighlighters().toSet())

        editor.caretModel.moveToOffset(source.indexOf("tail"))
        assertEquals(1, collections)
        assertTrue(innerGuide.isValid)
        assertTrue(innerPairHighlights.all { !it.isValid })
        val outerGuide = checkNotNull(activeGuide())
        assertSame(innerGuide, outerGuide)
        assertEquals(outer, activeGuideState()?.guide?.pair)
        assertEquals(1, guideHighlighters().size)
        assertEquals(originalBrackets, bracketColorHighlighters().toSet())
        assertEquals(4, bracketColorHighlighters().size)

        editor.caretModel.moveToOffset(0)
        assertEquals(1, collections)
        assertFalse(outerGuide.isValid)
        assertNull(activeGuide())
        assertTrue(activePairHighlighters().isEmpty())
        assertTrue(guideHighlighters().isEmpty())
        assertEquals(originalBrackets, bracketColorHighlighters().toSet())
    }

    fun testOnlyTheCurrentPrimaryCaretControlsTheActivePair() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("Sample.txt", source)
        val outer = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val inner = BracketPair(
            source.indexOf('('), 1, source.indexOf(')'), 1, 1, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("tail"))
        applyPass({ listOf(outer, inner) })

        assertEquals(outer, activeGuideState()?.guide?.pair)
        val secondary = editor.caretModel.addCaret(
            editor.offsetToVisualPosition(source.indexOf("inner")),
        )
        assertNotNull(secondary)
        assertEquals(source.indexOf("inner"), editor.caretModel.primaryCaret.offset)
        assertEquals(inner, activeGuideState()?.guide?.pair)

        editor.caretModel.removeCaret(checkNotNull(secondary))
        assertEquals(source.indexOf("tail"), editor.caretModel.primaryCaret.offset)
        assertEquals(outer, activeGuideState()?.guide?.pair)
    }

    fun testFeatureTogglesResolvePresentationOverlapWithoutReanalysis() {
        val source = "x { content } y"
        myFixture.configureByText("Sample.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 2, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))
        var options = BracketGuideSettings.getInstance().options.copy(
            showActivePairBorder = true,
            showActivePairBackground = true,
        )
        BracketGuideSettings.getInstance().replace(options)
        applyPass(pairs)

        val activePair = activePairHighlighters()
        assertEquals(2, activePair.size)
        assertTrue(activePair.all { it.layer == HighlighterLayer.ELEMENT_UNDER_CARET })
        assertTrue(activePair.all { it.textAttributesKey == null })
        assertEquals(
            setOf(
                pair.openOffset to pair.openOffset + pair.openTokenLength,
                pair.closeOffset to pair.closeOffset + pair.closeTokenLength,
            ),
            activePair.map { it.startOffset to it.endOffset }.toSet(),
        )
        val activeAttributes = checkNotNull(
            activePair.first().getTextAttributes(myFixture.editor.colorsScheme),
        )
        assertNull(activeAttributes.foregroundColor)
        assertEquals(
            BracketColorPalette.pairBackgroundColor(
                myFixture.editor.colorsScheme,
                options,
                pair.depth,
            ),
            activeAttributes.backgroundColor,
        )
        assertEquals(
            BracketColorPalette.baseColor(
                myFixture.editor.colorsScheme,
                options,
                pair.depth,
            ),
            activeAttributes.effectColor,
        )
        assertEquals(EffectType.BOXED, activeAttributes.effectType)

        options = options.copy(levelBaseColors = options.levelBaseColors.updated(2, 0x123456))
        applyOptions(options)
        assertTrue(
            bracketColorHighlighters().all {
                it.getTextAttributes(myFixture.editor.colorsScheme)?.foregroundColor ==
                    Color(0x123456)
            },
        )
        assertEquals(
            Color(0x123456),
            activePairHighlighters().first()
                .getTextAttributes(myFixture.editor.colorsScheme)
                ?.effectColor,
        )
        assertEquals(1, collections)

        options = options.copy(colorBracketTokens = false)
        applyOptions(options)
        assertTrue(bracketColorHighlighters().isEmpty())
        assertNotNull(activeGuide())

        options = options.copy(showActiveGuide = false)
        applyOptions(options)
        assertNull(activeGuide())
        assertEquals(2, activePairHighlighters().size)

        options = options.copy(
            showActiveGuide = true,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(options)
        assertNotNull(activeGuide())
        assertTrue(activePairHighlighters().isEmpty())

        options = options.copy(
            showActivePairBackground = true,
            pairBackgroundOpacityPercent = 0,
        )
        applyOptions(options)
        assertTrue(activePairHighlighters().isEmpty())

        options = options.copy(showActivePairBorder = true)
        applyOptions(options)
        val borderOnlyHighlights = activePairHighlighters()
        assertEquals(2, borderOnlyHighlights.size)
        assertTrue(
            borderOnlyHighlights.all {
                val attributes = it.getTextAttributes(myFixture.editor.colorsScheme)
                    ?: return@all false
                attributes.backgroundColor == null &&
                    attributes.effectColor != null &&
                    attributes.effectType != null
            },
        )

        options = options.copy(
            showActivePairBorder = true,
            showActivePairBackground = true,
            pairBackgroundOpacityPercent = BracketGuidePreferences().pairBackgroundOpacityPercent,
            useIndependentComponentColors = true,
            guideLineColors = options.guideLineColors.updated(2, 0x224466),
            pairBorderColors = options.pairBorderColors.updated(2, 0x335577),
            pairBackgroundColors = options.pairBackgroundColors.updated(2, 0x446688),
            showVerticalGuide = false,
            showHorizontalGuides = true,
            guideLineWidth = 3,
            guideOpacityPercent = 65,
        )
        applyOptions(options)
        val advancedAttributes = checkNotNull(
            activePairHighlighters().first().getTextAttributes(
                myFixture.editor.colorsScheme,
            ),
        )
        assertEquals(Color(0x335577), advancedAttributes.effectColor)
        assertEquals(
            BracketColorPalette.pairBackgroundColor(
                myFixture.editor.colorsScheme,
                options,
                pair.depth,
            ),
            advancedAttributes.backgroundColor,
        )
        options = options.copy(showHorizontalGuides = false)
        applyOptions(options)
        assertNull(activeGuide())
        assertEquals(2, activePairHighlighters().size)

        options = options.copy(enabled = false)
        applyOptions(options)
        assertNull(activeGuide())
        assertTrue(bracketColorHighlighters().isEmpty())
        assertTrue(activePairHighlighters().isEmpty())
        assertEquals(1, collections)
    }

    fun testGuideOnlyOptionsDoNotRebuildTheTokenWindow() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("GuideOnlyOptions.java", source)
        var visibleRangeRequests = 0
        applyPass(
            BracketGuideHighlightingPass(
                project = project,
                editor = myFixture.editor,
                fileType = myFixture.file.fileType,
                sourceFile = myFixture.file.virtualFile,
                analyze = service<BracketAnalysis>()::analyze,
                visibleRange = {
                    visibleRangeRequests++
                    TextRange(0, source.length)
                },
            ),
        )
        val tokenHighlighters = bracketColorHighlighters().toSet()
        val requestsAfterAnalysis = visibleRangeRequests

        session().updateOptions(
            BracketGuideSettings.getInstance().options.copy(
                guideLineWidth = 3,
                guideOpacityPercent = 60,
            ),
            refreshColors = false,
        )

        assertEquals(requestsAfterAnalysis, visibleRangeRequests)
        assertEquals(tokenHighlighters, bracketColorHighlighters().toSet())
    }

    fun testDisablingACappedTokenWindowRemovesDecorations() {
        val pairCount = 10_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DisabledCappedTokens.txt", source)
        val pairs = List(pairCount) { index ->
            val openOffset = index * 2
            BracketPair(openOffset, 1, openOffset + 1, 1, 0, 0, 0)
        }
        applyPass({ pairs }) { TextRange(0, source.length) }
        assertTrue(bracketColorHighlighters().size < pairs.size * 2)

        applyOptions(
            BracketGuideSettings.getInstance().options.copy(colorBracketTokens = false),
        )

        assertTrue(bracketColorHighlighters().isEmpty())
    }

    fun testReenablingBracketColorsRestoresTheCachedTokenIndex() {
        val source = "x { content } y"
        myFixture.configureByText("CachedTokens.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }

        applyPass(pairs)
        val originalRanges = bracketColorHighlighters().map { highlighter ->
            highlighter.startOffset to highlighter.endOffset
        }.toSet()
        assertTrue(originalRanges.isNotEmpty())

        val disabled = BracketGuideSettings.getInstance().options.copy(
            colorBracketTokens = false,
        )
        applyOptions(disabled)
        assertTrue(bracketColorHighlighters().isEmpty())

        applyOptions(disabled.copy(colorBracketTokens = true))

        assertEquals(
            originalRanges,
            bracketColorHighlighters().map { highlighter ->
                highlighter.startOffset to highlighter.endOffset
            }.toSet(),
        )
        assertEquals(1, collections)
    }

    fun testActiveToTokenOnlyRebuildsCompactAnalysisBeforeRestoringActivePresentation() {
        val source = "x { content } y"
        myFixture.configureByText("CompactTokenAnalysis.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        val fullOptions = BracketGuideSettings.getInstance().options

        applyPass(pairs)
        val fullStamp = stampFor(editor, fullOptions)
        val originalTokens = bracketColorHighlighters().toSet()
        assertEquals(1, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, fullStamp))
        assertNotNull(activeGuide())

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)

        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, fullStamp))
        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        assertEquals(originalTokens, bracketColorHighlighters().toSet())
        assertTrue(originalTokens.all { it.isValid })
        assertNull(activeGuide())

        applyPass(pairs)

        assertEquals(2, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        assertEquals(originalTokens, bracketColorHighlighters().toSet())

        applyOptions(fullOptions)
        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, fullStamp))
        applyPass(pairs)

        assertEquals(3, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, fullStamp))
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testTokenViewportKeepsFollowingCapabilityTransitionsBeforeBackgroundPasses() {
        val pairCount = 30_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("CapabilityTransitionViewport.txt", source)
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

        applyPass(pairs) { visibleRange }
        assertEquals(1, collections)

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)

        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertEquals(1, collections)
        assertTrue(
            "The unaccepted full snapshot should cover scrolling until compaction finishes",
            bracketColorHighlighters().any {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            },
        )

        applyPass(pairs) { visibleRange }
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)
        assertEquals(2, collections)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))

        applyOptions(fullOptions)
        visibleRange = TextRange(0, 256)
        session().visibleAreaChanged()

        assertEquals(2, collections)
        assertTrue(
            "The compact token index should remain usable while full analysis is pending",
            bracketColorHighlighters().any {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            },
        )
    }

    fun testReversingTokenOnlyTransitionBeforeCompactionReusesFullSnapshot() {
        myFixture.configureByText("ReversedCapabilityTransition.txt", "()".repeat(1_000))
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        var collections = 0
        val pairs = {
            collections++
            sequentialPairs(1_000)
        }
        val fullOptions = BracketGuideSettings.getInstance().options

        applyPass(pairs)
        assertEquals(1, collections)

        applyOptions(
            fullOptions.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
            ),
        )
        applyOptions(fullOptions)

        val fullStamp = stampFor(editor, fullOptions)
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, fullStamp))

        applyPass(pairs)

        assertEquals(1, collections)
    }
}
