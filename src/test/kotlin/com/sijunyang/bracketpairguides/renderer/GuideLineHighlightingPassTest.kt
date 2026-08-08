package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.analyzer.LanguageBraceMatchers
import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.awt.Color
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

class GuideLineHighlightingPassTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginOptions())
    }

    fun testCreatesReusesAndRemovesOwnedHighlighters() {
        val source =
            "class Sample { void run() { call(); } }"
        myFixture.configureByText(
            "Sample.java",
            source,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("()") + 1)
        val expectedPairCount = inReadAction {
            BracketPairAnalyzer(editor).collect(EmptyProgressIndicator()).size
        }

        applyPass()
        val first = ownedHighlighters()
        assertEquals(expectedPairCount * 2 + 1, first.size)
        assertEquals(1, first.count { it.customRenderer === BracketGuideRenderer })
        assertTrue(activePairHighlighters().isEmpty())
        assertEquals(
            expectedPairCount * 2,
            first.count { it.textAttributesKey in BracketColorPalette.LEVEL_KEYS },
        )

        applyPass()
        val second = ownedHighlighters()
        assertEquals(first.toSet(), second.toSet())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("class Sample {}")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        editor.caretModel.moveToOffset(editor.document.text.indexOf('{') + 1)
        applyPass()
        assertEquals(3, ownedHighlighters().size)
        assertEquals(
            1,
            ownedHighlighters().count { it.customRenderer === BracketGuideRenderer },
        )
        assertTrue(first.any { !it.isValid })

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("class Sample")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        applyPass()
        assertTrue(ownedHighlighters().isEmpty())
    }

    fun testHighlightingAcceptsAMockedPairProviderWithoutALanguageLexer() {
        val source = "opening content closing"
        myFixture.configureByText("Sample.txt", source)
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = "opening".length,
            closeOffset = source.indexOf("closing"),
            closeTokenLength = "closing".length,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        applyPass(BracketPairProvider { listOf(pair) })

        assertEquals(3, ownedHighlighters().size)
        assertEquals(
            1,
            ownedHighlighters().count { it.customRenderer === BracketGuideRenderer },
        )
    }

    fun testGuidePositionIndexRetainsOnlyTheMultilinePairEnvelope() {
        val pairSource = "{\n    value\n  }\n"
        val source = pairSource + "outside\n".repeat(5_000)
        myFixture.configureByText("BoundedGuidePositionIndex.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = 1,
            closeOffset = pairSource.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )

        val snapshot = inReadAction {
            AnalysisSnapshotBuilder.build(
                editor = editor,
                pairProvider = BracketPairProvider { listOf(pair) },
                stamp = AnalysisStamp.current(editor, AnalysisCapabilities.PREVIEW),
                progress = EmptyProgressIndicator(),
            )
        }
        val positionIndex = checkNotNull(snapshot.positionIndex)

        assertEquals(
            BracketGuide(pair, guideColumn = 2, anchorLine = 2),
            positionIndex.guideFor(pair),
        )
        assertEquals(
            null,
            positionIndex.guideForOrNull(
                pair.copy(openLine = 4_000, closeLine = 4_001),
            ),
        )
    }

    fun testInvalidProviderTokenBoundsDoNotCreateActivePresentation() {
        val source = "opening content closing"
        myFixture.configureByText("InvalidProviderPair.txt", source)
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = "opening".length,
            closeOffset = source.indexOf("closing"),
            closeTokenLength = source.length,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        PluginSettings.getInstance().replace(
            PluginSettings.getInstance().options.copy(showActivePairBorder = true),
        )

        applyPass(BracketPairProvider { listOf(pair) })

        assertNull(activeGuide())
        assertTrue(activePairHighlighters().isEmpty())
    }

    fun testStaleSnapshotResolverDoesNotUseALegacyFileTypeMatcher() {
        val source = "<root>content</root>"
        myFixture.configureByText("Unsupported.xml", source)
        val editor = myFixture.editor

        val resolution = inReadAction {
            EditorHighlighterActiveBracketPairResolver(myFixture.file.fileType)
                .findInnermost(editor, source.indexOf("content") + 2)
        }

        assertEquals(ActiveBracketPairResolution.Complete(null), resolution)
    }

    fun testCaretMovementResolvesActivePairBeforeTheFirstFullSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("InitialFastPath.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var resolutions = 0
        var collections = 0
        GuideLineHighlightingPass(
            project = project,
            editor = myFixture.editor,
            pairProvider = BracketPairProvider {
                collections++
                listOf(pair)
            },
            activePairResolver = ActiveBracketPairResolver { _, caretOffset ->
                resolutions++
                ActiveBracketPairResolution.Complete(
                    pair.takeIf {
                        caretOffset > it.openOffset &&
                            caretOffset < it.closeOffset + it.closeTokenLength
                    },
                )
            },
        )

        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        assertEquals(0, collections)
        assertEquals(1, resolutions)
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testStaleCaretMovementRevalidatesAnInwardScopeChange() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("StaleCaret.txt", source)
        val outer = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val inner = BracketPair(
            source.indexOf('('), 1, source.indexOf(')'), 1, 1, 0, 0,
        )
        val editor = myFixture.editor
        val tailOffset = source.indexOf("tail")
        val innerOffset = source.indexOf("inner")
        editor.caretModel.moveToOffset(tailOffset)
        val resolvedOffsets = ArrayList<Int>()
        val resolver = ActiveBracketPairResolver { _, caretOffset ->
            resolvedOffsets += caretOffset
            ActiveBracketPairResolution.Complete(
                when {
                    caretOffset > inner.openOffset &&
                        caretOffset < inner.closeOffset + inner.closeTokenLength -> inner
                    caretOffset > outer.openOffset &&
                        caretOffset < outer.closeOffset + outer.closeTokenLength -> outer
                    else -> null
                },
            )
        }
        applyPass(
            GuideLineHighlightingPass(
                project = project,
                editor = editor,
                pairProvider = BracketPairProvider { listOf(outer, inner) },
                activePairResolver = resolver,
            ),
        )
        assertEquals(outer, activeGuideState()?.guide?.pair)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        editor.caretModel.moveToOffset(innerOffset)

        assertEquals(listOf(tailOffset, innerOffset), resolvedOffsets)
        assertEquals(inner, activeGuideState()?.guide?.pair)
    }

    fun testEveryDocumentEditRevalidatesTheCurrentPair() {
        val source = "x { content } y"
        myFixture.configureByText("ContextChange.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        var resolvedPair: BracketPair? = pair
        var resolutions = 0
        val resolver = ActiveBracketPairResolver { _, _ ->
            resolutions++
            ActiveBracketPairResolution.Complete(resolvedPair)
        }
        applyPass(
            GuideLineHighlightingPass(
                project = project,
                editor = editor,
                pairProvider = BracketPairProvider { listOf(pair) },
                activePairResolver = resolver,
            ),
        )
        assertEquals(pair, activeGuideState()?.guide?.pair)

        resolvedPair = null
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content") + 1, "x")
        }

        assertEquals(1, resolutions)
        assertNull(activeGuide())
    }

    fun testIncompleteDocumentRevalidationKeepsTheAdjustedPair() {
        val source = "x { content } y"
        myFixture.configureByText("IncompleteFastPath.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        applyPass(
            GuideLineHighlightingPass(
                project = project,
                editor = editor,
                pairProvider = BracketPairProvider { listOf(pair) },
                activePairResolver = ActiveBracketPairResolver { _, _ ->
                    ActiveBracketPairResolution.Incomplete
                },
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content") + 1, "x")
        }

        assertEquals(
            pair.closeOffset + 1,
            activeGuideState()?.guide?.pair?.closeOffset,
        )
    }

    fun testDocumentChangesReleaseStaleAnalysisButKeepTokenPresentation() {
        val source = "class Sample { int value; }"
        myFixture.configureByText("ReleaseStaleSnapshot.java", source)
        val editor = myFixture.editor
        val options = PluginSettings.getInstance().options.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
            colorBracketTokens = true,
        )
        PluginSettings.getInstance().replace(options)
        applyPass()
        val acceptedStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(options),
            options.disabledLanguageIds,
        )
        val decorations = bracketColorHighlighters().toSet()
        assertTrue(decorations.isNotEmpty())
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, acceptedStamp))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("value"), "x")
        }

        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, acceptedStamp))
        assertEquals(decorations, bracketColorHighlighters().toSet())
        assertTrue(decorations.all { it.isValid })
    }

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
        val provider = BracketPairProvider {
            collections++
            listOf(outer, inner)
        }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("inner"))

        applyPass(provider)
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
        applyPass(BracketPairProvider { listOf(outer, inner) })

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
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))
        var options = PluginSettings.getInstance().options.copy(
            showActivePairBorder = true,
            showActivePairBackground = true,
        )
        PluginSettings.getInstance().replace(options)
        applyPass(provider)

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
            activeGuideState()?.color,
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
            pairBackgroundOpacityPercent = PluginSettings.DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
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
        assertEquals(
            GuideRenderOptions(
                showVertical = false,
                showHorizontal = true,
                lineWidth = 3,
                opacityPercent = 65,
            ),
            activeGuideState()?.options,
        )
        assertEquals(
            Color(0x224466),
            activeGuideState()?.color,
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
            BracketPairProvider {
                inReadAction {
                    BracketPairAnalyzer(myFixture.editor).collect(EmptyProgressIndicator())
                }
            },
        ) {
            visibleRangeRequests++
            TextRange(0, source.length)
        }
        val tokenHighlighters = bracketColorHighlighters().toSet()
        val requestsAfterAnalysis = visibleRangeRequests

        session().updateOptions(
            PluginSettings.getInstance().options.copy(
                guideLineWidth = 3,
                guideOpacityPercent = 60,
            ),
        )

        assertEquals(requestsAfterAnalysis, visibleRangeRequests)
        assertEquals(tokenHighlighters, bracketColorHighlighters().toSet())
    }

    fun testDisablingACappedTokenWindowClearsItsRefreshState() {
        val pairCount = 10_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DisabledCappedTokens.txt", source)
        val pairs = List(pairCount) { index ->
            val openOffset = index * 2
            BracketPair(openOffset, 1, openOffset + 1, 1, 0, 0, 0)
        }
        applyPass(BracketPairProvider { pairs }) { TextRange(0, source.length) }
        assertTrue(session().tokenDecorations.isCapped)

        applyOptions(
            PluginSettings.getInstance().options.copy(colorBracketTokens = false),
        )

        val disabled = session().tokenDecorations
        assertTrue(disabled.entries.isEmpty())
        assertFalse(disabled.isCapped)
        assertEquals(disabled.windowStartOffset, disabled.stableFocusStartOffset)
        assertEquals(disabled.windowEndOffset, disabled.stableFocusEndOffset)
    }

    fun testReenablingBracketColorsRestoresTheCachedTokenIndex() {
        val source = "x { content } y"
        myFixture.configureByText("CachedTokens.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }

        applyPass(provider)
        val originalRanges = bracketColorHighlighters().map { highlighter ->
            highlighter.startOffset to highlighter.endOffset
        }.toSet()
        assertTrue(originalRanges.isNotEmpty())

        val disabled = PluginSettings.getInstance().options.copy(
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
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        val fullOptions = PluginSettings.getInstance().options

        applyPass(provider)
        val fullStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(fullOptions),
            fullOptions.disabledLanguageIds,
        )
        val originalTokens = bracketColorHighlighters().toSet()
        assertEquals(1, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, fullStamp))
        assertNotNull(activeGuide())

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        val tokenOnlyStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(tokenOnlyOptions),
            tokenOnlyOptions.disabledLanguageIds,
        )

        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, fullStamp))
        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))
        assertEquals(originalTokens, bracketColorHighlighters().toSet())
        assertTrue(originalTokens.all { it.isValid })
        assertNull(activeGuide())

        applyPass(provider)

        assertEquals(2, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))
        assertEquals(originalTokens, bracketColorHighlighters().toSet())

        applyOptions(fullOptions)
        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, fullStamp))
        applyPass(provider)

        assertEquals(3, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, fullStamp))
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testTokenViewportKeepsFollowingCapabilityTransitionsBeforeBackgroundPasses() {
        val pairCount = 30_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("CapabilityTransitionViewport.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = sequentialPairs(pairCount)
        var collections = 0
        var visibleRange = TextRange(0, 256)
        val provider = BracketPairProvider {
            collections++
            pairs
        }
        val fullOptions = PluginSettings.getInstance().options

        applyPass(provider) { visibleRange }
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

        applyPass(provider) { visibleRange }
        val tokenOnlyStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(tokenOnlyOptions),
            tokenOnlyOptions.disabledLanguageIds,
        )
        assertEquals(2, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))

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
        val provider = BracketPairProvider {
            collections++
            sequentialPairs(1_000)
        }
        val fullOptions = PluginSettings.getInstance().options

        applyPass(provider)
        assertEquals(1, collections)

        applyOptions(
            fullOptions.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
            ),
        )
        applyOptions(fullOptions)

        val fullStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(fullOptions),
            fullOptions.disabledLanguageIds,
        )
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, fullStamp))

        applyPass(provider)

        assertEquals(1, collections)
    }

    fun testThemeRefreshUpdatesTokenColorsWithoutRebuildingHighlighters() {
        val source = "x { content } y"
        myFixture.configureByText("ThemeTokens.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val options = PluginOptions(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        PluginSettings.getInstance().replace(options)
        applyPass(
            BracketPairProvider {
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
                BracketColorPalette.LEVEL_KEYS[0],
                TextAttributes().apply { foregroundColor = refreshedColor },
            )
        }
        try {
            editor.setColorsScheme(refreshedScheme)
            session().updateOptions(
                options,
                resolveImmediately = false,
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
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        PluginSettings.getInstance().replace(PluginOptions(enabled = false))
        applyPass(provider)
        assertEquals(0, collections)
        assertTrue(ownedHighlighters().isEmpty())

        val enabled = PluginOptions()
        PluginSettings.getInstance().replace(enabled)
        session().updateOptions(enabled)
        applyPass(provider)
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
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        val enabled = PluginSettings.getInstance().options

        applyPass(provider)
        val acceptedStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(enabled),
            enabled.disabledLanguageIds,
        )
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, acceptedStamp))
        assertEquals(1, collections)

        applyOptions(enabled.copy(enabled = false))

        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, acceptedStamp))
        assertTrue(ownedHighlighters().isEmpty())
        applyPass(provider)
        assertEquals(1, collections)

        applyOptions(enabled)
        applyPass(provider)

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
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        val enabled = PluginSettings.getInstance().options
        val latePass = GuideLineHighlightingPass(project, editor, provider)
        inReadAction {
            latePass.doCollectInformation(EmptyProgressIndicator())
        }
        val fullStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(enabled),
            enabled.disabledLanguageIds,
        )

        val disabled = enabled.copy(enabled = false)
        applyOptions(disabled)
        latePass.doApplyInformationToEditor()
        val disabledStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(disabled),
            disabled.disabledLanguageIds,
        )

        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, fullStamp))
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, disabledStamp))
        assertTrue(ownedHighlighters().isEmpty())

        applyOptions(enabled)
        applyPass(provider)

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
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        val fullOptions = PluginSettings.getInstance().options
        val latePass = GuideLineHighlightingPass(project, editor, provider)
        inReadAction {
            latePass.doCollectInformation(EmptyProgressIndicator())
        }

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        val tokenOnlyStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(tokenOnlyOptions),
            tokenOnlyOptions.disabledLanguageIds,
        )
        latePass.doApplyInformationToEditor()

        assertEquals(1, collections)
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertFalse(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))

        applyPass(provider)

        assertEquals(2, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))
        assertTrue(bracketColorHighlighters().isNotEmpty())
    }

    fun testLateFullPassCannotReplaceAnAcceptedCompactTokenOnlySnapshot() {
        val pairCount = 30_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("LateFullAfterCompactTokens.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = sequentialPairs(pairCount)
        var collections = 0
        var visibleRange = TextRange(0, 256)
        val provider = BracketPairProvider {
            collections++
            pairs
        }
        val fullOptions = PluginSettings.getInstance().options
        val lateFullPass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProvider = provider,
            visibleRangeProvider = { visibleRange },
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
        applyPass(provider) { visibleRange }
        val tokenOnlyStamp = AnalysisStamp.current(
            editor,
            AnalysisCapabilities.from(tokenOnlyOptions),
            tokenOnlyOptions.disabledLanguageIds,
        )
        assertEquals(2, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))

        lateFullPass.doApplyInformationToEditor()

        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))
        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertEquals(2, collections)
        assertTrue(EditorGuideSession.hasAcceptedAnalysis(editor, tokenOnlyStamp))
        assertTrue(
            "A late full pass must not discard the compact viewport index",
            bracketColorHighlighters().any {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            },
        )
    }

    fun testReenablingActivePresentationUsesTheFastResolverImmediately() {
        val source = "x { content } y"
        myFixture.configureByText("ReenabledFastPath.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        var collections = 0
        var resolutions = 0
        PluginSettings.getInstance().replace(PluginOptions(enabled = false))
        applyPass(
            GuideLineHighlightingPass(
                project = project,
                editor = editor,
                pairProvider = BracketPairProvider {
                    collections++
                    listOf(pair)
                },
                activePairResolver = ActiveBracketPairResolver { _, _ ->
                    resolutions++
                    ActiveBracketPairResolution.Complete(pair)
                },
            ),
        )
        assertEquals(0, collections)
        assertNull(activeGuide())

        val enabled = PluginOptions()
        PluginSettings.getInstance().replace(enabled)
        session().updateOptions(enabled)

        assertEquals(0, collections)
        assertEquals(1, resolutions)
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testLanguageSelectionInvalidatesTheSnapshotAndUsesTheSameFastPathGate() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("LanguageSelection.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call") + 2)
        val capabilityId = checkNotNull(
            LanguageBraceMatchers.capabilityOwner(myFixture.file.language),
        ).id

        applyPass()
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertNotNull(activeGuide())

        val disabled = PluginSettings.getInstance().options.copy(
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
        assertNotNull("Fast path should restore the active guide immediately", activeGuide())

        applyPass()
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertNotNull(activeGuide())
    }

    fun testViewportBoundsMarkupForFiftyThousandPairsAndScrollReusesRecognition() {
        val pairCount = 50_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("Viewport.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }
        var collections = 0
        var visibleRange = TextRange(0, 256)
        val provider = BracketPairProvider {
            collections++
            pairs
        }

        applyPass(provider) { visibleRange }

        assertEquals(1, collections)
        assertTrue(bracketColorHighlighters().isNotEmpty())
        assertTrue(bracketColorHighlighters().size <= 1_024)

        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertEquals(1, collections)
        val scrolledDecorations = bracketColorHighlighters()
        assertTrue(scrolledDecorations.isNotEmpty())
        assertTrue(scrolledDecorations.size <= 1_536)
        assertTrue(
            scrolledDecorations.any {
                it.startOffset in visibleRange.startOffset until visibleRange.endOffset
            },
        )
    }

    fun testOversizedDenseViewportStaysAnchoredAwayFromCaretAndCapsDecorations() {
        val pairCount = 50_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DenseViewport.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }

        applyPass(BracketPairProvider { pairs }) {
            TextRange(50_000, source.length)
        }

        val decorations = bracketColorHighlighters()
        assertTrue(decorations.isNotEmpty())
        assertTrue(decorations.size <= MAX_VISIBLE_TOKEN_DECORATIONS)
        assertTrue(
            "Decorations should follow the reported viewport instead of the off-screen caret",
            decorations.minOf { it.startOffset } > 50_000,
        )
    }

    fun testCappedDenseDecorationsRecenterWhenScrollingInsideTheCachedPadding() {
        val pairCount = 25_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DenseViewportScroll.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }
        var visibleRange = TextRange(20_000, 36_384)

        applyPass(BracketPairProvider { pairs }) { visibleRange }
        val initialDecorations = bracketColorHighlighters()
        assertEquals(MAX_VISIBLE_TOKEN_DECORATIONS, initialDecorations.size)
        val initialLastOffset = initialDecorations.maxOf { it.startOffset }

        // This viewport still fits in the first padded character window. The
        // capped token slice nevertheless has to follow its new center.
        visibleRange = TextRange(24_000, 40_384)
        session().visibleAreaChanged()

        val scrolledDecorations = bracketColorHighlighters()
        assertEquals(MAX_VISIBLE_TOKEN_DECORATIONS, scrolledDecorations.size)
        assertTrue(
            "Capped decorations must be recentered within a cached character window",
            scrolledDecorations.minOf { it.startOffset } > initialLastOffset,
        )
    }

    fun testCappedDenseDecorationsFollowCaretMovementWithoutScrolling() {
        val pairCount = 5_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DenseViewportCaret.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }

        var viewportRequests = 0
        applyPass(BracketPairProvider { pairs }) {
            viewportRequests++
            TextRange(0, source.length)
        }
        val initialDecorations = bracketColorHighlighters()
        assertEquals(MAX_VISIBLE_TOKEN_DECORATIONS, initialDecorations.size)
        val initialLastOffset = initialDecorations.maxOf { it.startOffset }
        val initialViewportRequests = viewportRequests

        repeat(8) { step ->
            editor.caretModel.moveToOffset(source.length - 1 - step * 2)
        }

        PlatformTestUtil.waitWithEventsDispatching(
            "capped token window follows caret",
            {
                bracketColorHighlighters().minOfOrNull { it.startOffset }
                    ?.let { it > initialLastOffset } == true &&
                    viewportRequests == initialViewportRequests + 1
            },
            10_000,
        )
        assertEquals(MAX_VISIBLE_TOKEN_DECORATIONS, bracketColorHighlighters().size)
        assertEquals(initialViewportRequests + 1, viewportRequests)
    }

    fun testAppliesActiveGuideBeforeRequestingViewportDecorations() {
        val source = "x { content } y"
        myFixture.configureByText("ActiveFirst.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        PluginSettings.getInstance().replace(
            PluginSettings.getInstance().options.copy(showActivePairBorder = true),
        )
        var activePairWhenViewportWasRequested: BracketPair? = null
        var activeHighlightsWhenViewportWasRequested = 0
        val pass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProvider = BracketPairProvider { listOf(pair) },
            visibleRangeProvider = {
                activePairWhenViewportWasRequested = activeGuideState()?.guide?.pair
                activeHighlightsWhenViewportWasRequested = activePairHighlighters().size
                TextRange(0, source.length)
            },
        )

        applyPass(pass)

        assertEquals(pair, activePairWhenViewportWasRequested)
        assertEquals(2, activeHighlightsWhenViewportWasRequested)
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testBackgroundPassConstructionAndDedupDoNotReadPresentationState() {
        val source = "x { content } y"
        myFixture.configureByText("BackgroundDedup.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val collections = AtomicInteger()
        val provider = BracketPairProvider {
            collections.incrementAndGet()
            listOf(pair)
        }
        EditorGuideSession.dispose(editor)
        assertNull(EditorGuideSession.get(editor))
        fun collectInBackground(): GuideLineHighlightingPass {
            val collection = AppExecutorUtil.getAppExecutorService()
                .submit<GuideLineHighlightingPass> {
                    inReadAction {
                        GuideLineHighlightingPass(project, editor, provider).also { pass ->
                            pass.doCollectInformation(EmptyProgressIndicator())
                        }
                    }
                }
            PlatformTestUtil.waitWithEventsDispatching(
                "background guide collection",
                { collection.isDone },
                10_000,
            )
            return collection.get()
        }

        val initialPass = collectInBackground()
        assertNull(EditorGuideSession.get(editor))
        assertEquals(1, collections.get())
        initialPass.doApplyInformationToEditor()
        val acceptedSession = session()
        assertEquals(pair, activeGuideState()?.guide?.pair)

        val deduplicatedPass = collectInBackground()

        assertSame(acceptedSession, session())
        assertEquals(1, collections.get())
        deduplicatedPass.doApplyInformationToEditor()
        assertSame(acceptedSession, session())
        assertEquals(pair, activeGuideState()?.guide?.pair)
    }

    fun testBackgroundProviderUsesStampedLanguageSelectionAcrossAbaChange() {
        val source = "x { content } y"
        myFixture.configureByText("LanguageSelectionSnapshot.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val initialOptions = PluginSettings.getInstance().options
        val disabledDuringCollection = setOf("test.matcher.family")
        val providerEntered = CountDownLatch(1)
        val continueCollection = CountDownLatch(1)
        val capturedDisabledLanguageIds = AtomicReference<Set<String>>()
        val observedGlobalLanguageIds = AtomicReference<Set<String>>()
        val pass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProviderFactory = { disabledLanguageIds ->
                capturedDisabledLanguageIds.set(disabledLanguageIds)
                BracketPairProvider {
                    providerEntered.countDown()
                    check(continueCollection.await(10, TimeUnit.SECONDS))
                    observedGlobalLanguageIds.set(
                        PluginSettings.getInstance().options.disabledLanguageIds,
                    )
                    if (disabledDuringCollection.single() in disabledLanguageIds) {
                        emptyList()
                    } else {
                        listOf(pair)
                    }
                }
            },
        )
        val collection = AppExecutorUtil.getAppExecutorService().submit<Unit> {
            inReadAction {
                pass.doCollectInformation(EmptyProgressIndicator())
            }
        }

        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "background provider entry",
                { providerEntered.count == 0L },
                10_000,
            )
            PluginSettings.getInstance().replace(
                initialOptions.copy(disabledLanguageIds = disabledDuringCollection),
            )
            continueCollection.countDown()
            PlatformTestUtil.waitWithEventsDispatching(
                "stamped language collection",
                { collection.isDone },
                10_000,
            )
            collection.get()
            PluginSettings.getInstance().replace(initialOptions)

            pass.doApplyInformationToEditor()

            assertEquals(initialOptions.disabledLanguageIds, capturedDisabledLanguageIds.get())
            assertEquals(disabledDuringCollection, observedGlobalLanguageIds.get())
            assertEquals(pair, activeGuideState()?.guide?.pair)
        } finally {
            continueCollection.countDown()
            PluginSettings.getInstance().replace(initialOptions)
            collection.cancel(true)
        }
    }

    fun testStaleBackgroundPassDoesNotInstallItsDependenciesIntoANewSession() {
        val source = "x { content } y"
        myFixture.configureByText("StaleBackgroundInstall.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        EditorGuideSession.dispose(editor)
        assertNull(EditorGuideSession.get(editor))
        val staleResolverCalls = AtomicInteger()
        val staleCollection = AppExecutorUtil.getAppExecutorService()
            .submit<GuideLineHighlightingPass> {
                inReadAction {
                    GuideLineHighlightingPass(
                        project = project,
                        editor = editor,
                        pairProvider = BracketPairProvider { listOf(pair) },
                        activePairResolver = ActiveBracketPairResolver { _, _ ->
                            staleResolverCalls.incrementAndGet()
                            ActiveBracketPairResolution.Complete(pair)
                        },
                    ).also { pass ->
                        pass.doCollectInformation(EmptyProgressIndicator())
                    }
                }
            }
        PlatformTestUtil.waitWithEventsDispatching(
            "stale background collection",
            { staleCollection.isDone },
            10_000,
        )
        val stalePass = staleCollection.get()
        assertNull(EditorGuideSession.get(editor))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        stalePass.doApplyInformationToEditor()

        assertNull(EditorGuideSession.get(editor))
        assertEquals(0, staleResolverCalls.get())

        var currentResolverCalls = 0
        applyPass(
            GuideLineHighlightingPass(
                project = project,
                editor = editor,
                pairProvider = BracketPairProvider { listOf(pair) },
                activePairResolver = ActiveBracketPairResolver { _, _ ->
                    currentResolverCalls++
                    ActiveBracketPairResolution.Complete(pair)
                },
            ),
        )
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }

        assertEquals(0, staleResolverCalls.get())
        assertEquals(1, currentResolverCalls)
    }

    fun testRejectedStalePassDoesNotReplaceCurrentSessionDependencies() {
        val source = "x { content } y"
        myFixture.configureByText("DependencyOrder.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        var staleVisibleRangeCalls = 0
        var currentVisibleRangeCalls = 0
        var staleResolverCalls = 0
        var currentResolverCalls = 0
        val stalePass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProvider = BracketPairProvider { listOf(pair) },
            visibleRangeProvider = {
                staleVisibleRangeCalls++
                TextRange(0, it.document.textLength)
            },
            activePairResolver = ActiveBracketPairResolver { _, _ ->
                staleResolverCalls++
                ActiveBracketPairResolution.Complete(pair)
            },
        )
        inReadAction {
            stalePass.doCollectInformation(EmptyProgressIndicator())
        }

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        val currentPass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProvider = BracketPairProvider { listOf(pair) },
            visibleRangeProvider = {
                currentVisibleRangeCalls++
                TextRange(0, it.document.textLength)
            },
            activePairResolver = ActiveBracketPairResolver { _, _ ->
                currentResolverCalls++
                ActiveBracketPairResolution.Complete(pair)
            },
        )
        applyPass(currentPass)

        stalePass.doApplyInformationToEditor()
        staleVisibleRangeCalls = 0
        currentVisibleRangeCalls = 0
        staleResolverCalls = 0
        currentResolverCalls = 0

        session().visibleAreaChanged()
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }

        assertEquals(0, staleVisibleRangeCalls)
        assertEquals(1, currentVisibleRangeCalls)
        assertEquals(0, staleResolverCalls)
        assertEquals(1, currentResolverCalls)
    }

    fun testDocumentEditAdjustsTheActiveGuideBeforeFullRecognitionCompletes() {
        val source = "x { active content } y"
        myFixture.configureByText("Priority.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val provider = BracketPairProvider {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        applyPass(provider)
        val guideHighlighter = checkNotNull(activeGuide())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content"), "fast ")
        }

        assertEquals(1, collections)
        assertSame(guideHighlighter, activeGuide())
        assertEquals(
            pair.closeOffset + "fast ".length,
            activeGuideState()?.guide?.pair?.closeOffset,
        )
    }

    fun testDocumentEditRecalculatesVerticalGuideColumnImmediately() {
        val source = """
            class Sample {
              void run() {
                call();
              }
            }
        """.trimIndent()
        myFixture.configureByText("ImmediateColumn.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        applyPass()

        assertEquals(
            2,
            activeGuideState()?.guide?.guideColumn,
        )
        val closeLineStart = source.indexOf("  }\n}")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(closeLineStart, "  ")
        }

        val guide = checkNotNull(
            activeGuideState()?.guide,
        )
        assertEquals(4, guide.guideColumn)
        assertEquals(
            editor.document.text.indexOf('}', closeLineStart),
            guide.pair.closeOffset,
        )
    }

    fun testNewMultilineLayoutGetsAnImmediateGuideColumnWithoutAFullPass() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("ImmediateMultiline.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        applyPass()
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(
                openingBrace + 1,
                closingBrace,
                "\n    call();\n  ",
            )
        }

        val guide = checkNotNull(
            activeGuideState()?.guide,
        )
        assertEquals(2, guide.guideColumn)
        assertEquals(0, guide.pair.openLine)
        assertEquals(2, guide.pair.closeLine)
    }

    fun testBracketEditResolvesTheNewInnermostPairBeforeAFullPass() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("ImmediatePair.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call") + 2)
        applyPass()
        val start = source.indexOf("call")
        val end = source.indexOf(';', start) + 1

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(end, ")")
            editor.document.insertString(start, "(")
        }

        val pair = checkNotNull(
            activeGuideState()?.guide?.pair,
        )
        assertEquals(start, pair.openOffset)
        assertEquals(end + 1, pair.closeOffset)
        assertEquals(2, pair.depth)
    }

    fun testRapidPairSwitchesReuseOneGuideHighlighter() {
        val pairCount = 10_000
        val source = "(x)".repeat(pairCount)
        myFixture.configureByText("Switches.txt", source)
        val pairs = List(pairCount) { index ->
            val openOffset = index * 3
            BracketPair(openOffset, 1, openOffset + 2, 1, 0, 0, 0)
        }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        applyPass(BracketPairProvider { pairs }) { TextRange(0, 256) }
        val persistentGuide = checkNotNull(activeGuide())

        val elapsed = measureTimeMillis {
            var index = 1
            while (index <= 2_000) {
                editor.caretModel.moveToOffset(index * 3 + 1)
                index++
            }
        }

        assertSame(persistentGuide, activeGuide())
        assertEquals(1, guideHighlighters().size)
        assertTrue("2k active-pair switches took ${elapsed}ms", elapsed < 2_000)
    }

    private fun applyPass(
        pairProvider: BracketPairProvider? = null,
        visibleRangeProvider: ((Editor) -> TextRange)? = null,
    ) {
        val pass = if (pairProvider == null) {
            GuideLineHighlightingPass(project, myFixture.editor)
        } else if (visibleRangeProvider == null) {
            GuideLineHighlightingPass(project, myFixture.editor, pairProvider)
        } else {
            GuideLineHighlightingPass(
                project,
                myFixture.editor,
                pairProvider,
                visibleRangeProvider,
            )
        }
        applyPass(pass)
    }

    private fun applyPass(pass: GuideLineHighlightingPass) {
        inReadAction {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
    }

    private fun ownedHighlighters(): List<RangeHighlighter> {
        val session = session()
        return buildList {
            addAll(session.tokenDecorations.entries.map(VisibleTokenEntry::highlighter))
            addAll(session.activePairHighlights)
            session.activeGuide?.let(::add)
        }
    }

    private fun guideHighlighters(): List<RangeHighlighter> {
        return listOfNotNull(session().activeGuide)
    }

    private fun bracketColorHighlighters(): List<RangeHighlighter> {
        return session().tokenDecorations.entries.map(VisibleTokenEntry::highlighter)
    }

    private fun activePairHighlighters(): List<RangeHighlighter> {
        return session().activePairHighlights
    }

    private fun activeGuide(): RangeHighlighter? {
        return guideHighlighters().singleOrNull {
            it.customRenderer === BracketGuideRenderer
        }
    }

    private fun activeGuideState(): GuidePaintState? =
        activeGuide()?.getUserData(GUIDE_PAINT_STATE_KEY)

    private fun session(): EditorGuideSession =
        checkNotNull(EditorGuideSession.get(myFixture.editor))

    private fun applyOptions(options: PluginOptions) {
        PluginSettings.getInstance().replace(options)
        session().updateOptions(options)
    }

    private fun sequentialPairs(pairCount: Int): List<BracketPair> =
        List(pairCount) { index ->
            val openOffset = index * 2
            BracketPair(openOffset, 1, openOffset + 1, 1, 0, 0, 0)
        }

    private fun List<Int>.updated(index: Int, value: Int): List<Int> =
        toMutableList().also { it[index] = value }

    private fun <T> inReadAction(action: () -> T): T {
        return ReadAction.compute<T, RuntimeException>(action)
    }
}
