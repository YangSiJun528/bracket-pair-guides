package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.presentation.BracketColorPalette
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat
import java.awt.Color
import java.awt.image.BufferedImage

internal class GuidePresentationTest : BracketGuideHighlightingFixture() {
    fun testGuideRendererUpdatesInPlaceUntilTheGuideIsCleared() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("RendererUpdates.txt", source)
        val outer =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
            )
        val inner =
            BracketPair(
                source.indexOf('('),
                1,
                source.indexOf(')'),
                1,
                1,
                0,
                0,
            )
        val editor = myFixture.editor
        var rendererChanges = 0
        (editor.markupModel as MarkupModelEx).addMarkupModelListener(
            testRootDisposable,
            object : MarkupModelListener {
                override fun attributesChanged(
                    highlighter: RangeHighlighterEx,
                    renderersChanged: Boolean,
                    fontStyleChanged: Boolean,
                    foregroundColorChanged: Boolean,
                ) {
                    if (renderersChanged && highlighter.customRenderer is BracketGuideDrawing) {
                        rendererChanges++
                    }
                }
            },
        )

        editor.caretModel.moveToOffset(source.indexOf("inner"))
        applyPass({ listOf(outer, inner) })
        val persistentMark = checkNotNull(activeGuide())
        val persistentRenderer = checkNotNull(activeGuideState())
        assertThat(persistentRenderer.guide.pair).isEqualTo(inner)
        assertThat(rendererChanges).isEqualTo(1)

        editor.caretModel.moveToOffset(source.indexOf("tail"))
        assertThat(activeGuide()).isSameAs(persistentMark)
        assertThat(activeGuideState()).isSameAs(persistentRenderer)
        assertThat(persistentRenderer.guide.pair).isEqualTo(outer)
        assertThat(rendererChanges).isEqualTo(1)

        repeat(100) {
            editor.caretModel.moveToOffset(source.indexOf("inner"))
            editor.caretModel.moveToOffset(source.indexOf("tail"))
        }
        assertThat(activeGuide()).isSameAs(persistentMark)
        assertThat(activeGuideState()).isSameAs(persistentRenderer)
        assertThat(persistentRenderer.guide.pair).isEqualTo(outer)
        assertThat(rendererChanges).isEqualTo(1)

        val configuredColor = Color(0xCC, 0x22, 0x55)
        val options =
            BracketGuideSettings.getInstance().options.copy(
                useIndependentComponentColors = true,
                guideLineColors =
                BracketGuideSettings
                    .getInstance()
                    .options.guideLineColors
                    .updated(outer.depth, configuredColor.rgb and 0xFFFFFF),
                guideLineWidth = 4,
                guideOpacityPercent = 100,
            )
        applyOptions(options)
        session().updateOptions(options, refreshColors = true)

        assertThat(activeGuide()).isSameAs(persistentMark)
        assertThat(activeGuideState()).isSameAs(persistentRenderer)
        assertThat(rendererChanges).isEqualTo(1)
        val image = BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            persistentRenderer.paint(editor, persistentMark, graphics)
        } finally {
            graphics.dispose()
        }
        assertThat(image.containsColor(configuredColor)).isTrue()

        editor.caretModel.moveToOffset(0)
        assertThat(persistentMark.isValid).isFalse()
        assertThat(activeGuide()).isNull()

        editor.caretModel.moveToOffset(source.indexOf("tail"))
        assertThat(activeGuide()).isNotSameAs(persistentMark)
        assertThat(activeGuideState()).isNotSameAs(persistentRenderer)
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(outer)
        assertThat(rendererChanges).isEqualTo(2)
    }

    fun testCaretMovementReplacesOnlyActivePresentationUsingCachedRecognition() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("Sample.txt", source)
        val outer =
            BracketPair(
                openOffset = source.indexOf('{'),
                openTokenLength = 1,
                closeOffset = source.indexOf('}'),
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        val inner =
            BracketPair(
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
        assertThat(innerGuide.startOffset).isEqualTo(0)
        assertThat(innerGuide.endOffset).isEqualTo(editor.document.textLength)
        val innerPairHighlights = activePairHighlighters().toSet()
        assertThat(innerPairHighlights).isEmpty()
        val originalBrackets = bracketColorHighlighters().toSet()
        assertThat(collections).isEqualTo(1)
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(inner)
        assertThat(bracketColorHighlighters()).hasSize(4)

        editor.caretModel.moveToOffset(source.indexOf("inner") + 1)
        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isEqualTo(innerGuide)
        assertThat(activePairHighlighters().toSet()).isEqualTo(innerPairHighlights)

        editor.caretModel.moveToOffset(source.indexOf("tail"))
        assertThat(collections).isEqualTo(1)
        assertThat(innerGuide.isValid).isTrue()
        assertThat(innerPairHighlights).allMatch { !it.isValid }
        val outerGuide = checkNotNull(activeGuide())
        assertThat(outerGuide).isSameAs(innerGuide)
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(outer)
        assertThat(guideHighlighters()).hasSize(1)
        assertThat(bracketColorHighlighters().toSet()).isEqualTo(originalBrackets)
        assertThat(bracketColorHighlighters()).hasSize(4)

        editor.caretModel.moveToOffset(0)
        assertThat(collections).isEqualTo(1)
        assertThat(outerGuide.isValid).isFalse()
        assertThat(activeGuide()).isNull()
        assertThat(activePairHighlighters()).isEmpty()
        assertThat(guideHighlighters()).isEmpty()
        assertThat(bracketColorHighlighters().toSet()).isEqualTo(originalBrackets)
    }

    fun testOnlyTheCurrentPrimaryCaretControlsTheActivePair() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("Sample.txt", source)
        val outer =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
            )
        val inner =
            BracketPair(
                source.indexOf('('),
                1,
                source.indexOf(')'),
                1,
                1,
                0,
                0,
            )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("tail"))
        applyPass({ listOf(outer, inner) })

        assertThat(activeGuideState()?.guide?.pair).isEqualTo(outer)
        val secondary =
            editor.caretModel.addCaret(
                editor.offsetToVisualPosition(source.indexOf("inner")),
            )
        assertThat(secondary).isNotNull()
        assertThat(editor.caretModel.primaryCaret.offset).isEqualTo(source.indexOf("inner"))
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(inner)

        editor.caretModel.removeCaret(checkNotNull(secondary))
        assertThat(editor.caretModel.primaryCaret.offset).isEqualTo(source.indexOf("tail"))
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(outer)
    }

    fun testFeatureTogglesResolvePresentationOverlapWithoutReanalysis() {
        val source = "x { content } y"
        myFixture.configureByText("Sample.txt", source)
        val pair =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                2,
                0,
                0,
            )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))
        var options =
            BracketGuideSettings.getInstance().options.copy(
                showActivePairBorder = true,
                showActivePairBackground = true,
            )
        BracketGuideSettings.getInstance().replace(options)
        applyPass(pairs)

        val activePair = activePairHighlighters()
        assertThat(activePair).hasSize(2)
        assertThat(activePair).allMatch { it.layer == HighlighterLayer.ELEMENT_UNDER_CARET }
        assertThat(activePair).allMatch { it.textAttributesKey == null }
        assertThat(
            activePair.map { it.startOffset to it.endOffset }.toSet(),
        ).isEqualTo(
            setOf(
                pair.openOffset to pair.openOffset + pair.openTokenLength,
                pair.closeOffset to pair.closeOffset + pair.closeTokenLength,
            ),
        )
        val activeAttributes =
            checkNotNull(
                activePair.first().getTextAttributes(myFixture.editor.colorsScheme),
            )
        assertThat(activeAttributes.foregroundColor).isNull()
        assertThat(
            activeAttributes.backgroundColor,
        ).isEqualTo(
            BracketColorPalette.pairBackgroundColor(
                myFixture.editor.colorsScheme,
                options,
                pair.depth,
            ),
        )
        assertThat(
            activeAttributes.effectColor,
        ).isEqualTo(
            BracketColorPalette.baseColor(
                options,
                pair.depth,
            ),
        )
        assertThat(activeAttributes.effectType).isEqualTo(EffectType.BOXED)

        options = options.copy(levelBaseColors = options.levelBaseColors.updated(2, 0x123456))
        applyOptions(options)
        assertThat(bracketColorHighlighters()).allMatch {
            it.getTextAttributes(myFixture.editor.colorsScheme)?.foregroundColor ==
                Color(0x123456)
        }
        assertThat(
            activePairHighlighters()
                .first()
                .getTextAttributes(myFixture.editor.colorsScheme)
                ?.effectColor,
        ).isEqualTo(Color(0x123456))
        assertThat(collections).isEqualTo(1)

        options = options.copy(colorBracketTokens = false)
        applyOptions(options)
        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(activeGuide()).isNotNull()

        options = options.copy(showActiveGuide = false)
        applyOptions(options)
        assertThat(activeGuide()).isNull()
        assertThat(activePairHighlighters()).hasSize(2)

        options =
            options.copy(
                showActiveGuide = true,
                showActivePairBorder = false,
                showActivePairBackground = false,
            )
        applyOptions(options)
        assertThat(activeGuide()).isNotNull()
        assertThat(activePairHighlighters()).isEmpty()

        options =
            options.copy(
                showActivePairBackground = true,
                pairBackgroundOpacityPercent = 0,
            )
        applyOptions(options)
        assertThat(activePairHighlighters()).isEmpty()

        options = options.copy(showActivePairBorder = true)
        applyOptions(options)
        val borderOnlyHighlights = activePairHighlighters()
        assertThat(borderOnlyHighlights).hasSize(2)
        assertThat(borderOnlyHighlights).allMatch { highlighter ->
            highlighter.getTextAttributes(myFixture.editor.colorsScheme)?.let { attributes ->
                attributes.backgroundColor == null &&
                    attributes.effectColor != null &&
                    attributes.effectType != null
            } == true
        }

        options =
            options.copy(
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
        val advancedAttributes =
            checkNotNull(
                activePairHighlighters().first().getTextAttributes(
                    myFixture.editor.colorsScheme,
                ),
            )
        assertThat(advancedAttributes.effectColor).isEqualTo(Color(0x335577))
        assertThat(
            advancedAttributes.backgroundColor,
        ).isEqualTo(
            BracketColorPalette.pairBackgroundColor(
                myFixture.editor.colorsScheme,
                options,
                pair.depth,
            ),
        )
        options = options.copy(showHorizontalGuides = false)
        applyOptions(options)
        assertThat(activeGuide()).isNull()
        assertThat(activePairHighlighters()).hasSize(2)

        options = options.copy(enabled = false)
        applyOptions(options)
        assertThat(activeGuide()).isNull()
        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(activePairHighlighters()).isEmpty()
        assertThat(collections).isEqualTo(1)
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

        assertThat(visibleRangeRequests).isEqualTo(requestsAfterAnalysis)
        assertThat(bracketColorHighlighters().toSet()).isEqualTo(tokenHighlighters)
    }

    fun testDisablingACappedTokenWindowRemovesDecorations() {
        val pairCount = 10_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DisabledCappedTokens.txt", source)
        val pairs =
            List(pairCount) { index ->
                val openOffset = index * 2
                BracketPair(openOffset, 1, openOffset + 1, 1, 0, 0, 0)
            }
        applyPass({ pairs }) { TextRange(0, source.length) }
        assertThat(bracketColorHighlighters().size).isLessThan(pairs.size * 2)

        applyOptions(
            BracketGuideSettings.getInstance().options.copy(colorBracketTokens = false),
        )

        assertThat(bracketColorHighlighters()).isEmpty()
    }

    fun testReenablingBracketColorsRestoresTheCachedTokenIndex() {
        val source = "x { content } y"
        myFixture.configureByText("CachedTokens.txt", source)
        val pair =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
            )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }

        applyPass(pairs)
        val originalRanges =
            bracketColorHighlighters()
                .map { highlighter ->
                    highlighter.startOffset to highlighter.endOffset
                }.toSet()
        assertThat(originalRanges).isNotEmpty()

        val disabled =
            BracketGuideSettings.getInstance().options.copy(
                colorBracketTokens = false,
            )
        applyOptions(disabled)
        assertThat(bracketColorHighlighters()).isEmpty()

        applyOptions(disabled.copy(colorBracketTokens = true))

        assertThat(
            bracketColorHighlighters()
                .map { highlighter ->
                    highlighter.startOffset to highlighter.endOffset
                }.toSet(),
        ).isEqualTo(originalRanges)
        assertThat(collections).isEqualTo(1)
    }

    fun testActiveToTokenOnlyRebuildsCompactAnalysisBeforeRestoringActivePresentation() {
        val source = "x { content } y"
        myFixture.configureByText("CompactTokenAnalysis.txt", source)
        val pair =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
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
        assertThat(collections).isEqualTo(1)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, fullStamp)).isTrue()
        assertThat(activeGuide()).isNotNull()

        val tokenOnlyOptions =
            fullOptions.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
            )
        applyOptions(tokenOnlyOptions)
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)

        assertThat(EditorGuideSessions.canSkipAnalysis(editor, fullStamp)).isFalse()
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isFalse()
        assertThat(bracketColorHighlighters().toSet()).isEqualTo(originalTokens)
        assertThat(originalTokens).allMatch { it.isValid }
        assertThat(activeGuide()).isNull()

        applyPass(pairs)

        assertThat(collections).isEqualTo(2)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isTrue()
        assertThat(bracketColorHighlighters().toSet()).isEqualTo(originalTokens)

        applyOptions(fullOptions)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, fullStamp)).isFalse()
        applyPass(pairs)

        assertThat(collections).isEqualTo(3)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, fullStamp)).isTrue()
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(pair)
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
        assertThat(collections).isEqualTo(1)

        val tokenOnlyOptions =
            fullOptions.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
            )
        applyOptions(tokenOnlyOptions)

        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertThat(collections).isEqualTo(1)
        assertThat(bracketColorHighlighters())
            .describedAs(
                "The unaccepted full snapshot should cover scrolling until compaction finishes",
            ).anyMatch {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            }

        applyPass(pairs) { visibleRange }
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)
        assertThat(collections).isEqualTo(2)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp)).isTrue()

        applyOptions(fullOptions)
        visibleRange = TextRange(0, 256)
        session().visibleAreaChanged()

        assertThat(collections).isEqualTo(2)
        assertThat(bracketColorHighlighters())
            .describedAs("The compact token index should remain usable while full analysis is pending")
            .anyMatch {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            }
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
        assertThat(collections).isEqualTo(1)

        applyOptions(
            fullOptions.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
            ),
        )
        applyOptions(fullOptions)

        val fullStamp = stampFor(editor, fullOptions)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, fullStamp)).isTrue()

        applyPass(pairs)

        assertThat(collections).isEqualTo(1)
    }
}

private fun BufferedImage.containsColor(color: Color): Boolean {
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (getRGB(x, y) == color.rgb) return true
        }
    }
    return false
}
