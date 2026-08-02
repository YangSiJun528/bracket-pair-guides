package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.awt.Color
import kotlin.system.measureTimeMillis

class GuideLineHighlightingPassTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginSettings.State())
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
        assertEquals(inner, activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair)
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
        assertEquals(outer, activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair)
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

        assertEquals(outer, activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair)
        val secondary = editor.caretModel.addCaret(
            editor.offsetToVisualPosition(source.indexOf("inner")),
        )
        assertNotNull(secondary)
        assertEquals(source.indexOf("inner"), editor.caretModel.primaryCaret.offset)
        assertEquals(inner, activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair)

        editor.caretModel.removeCaret(checkNotNull(secondary))
        assertEquals(source.indexOf("tail"), editor.caretModel.primaryCaret.offset)
        assertEquals(outer, activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair)
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
        val settings = PluginSettings.getInstance().state
        settings.showActivePairBorder = true
        settings.showActivePairBackground = true
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
                PluginSettings.getInstance().state,
                pair.depth,
            ),
            activeAttributes.backgroundColor,
        )
        assertEquals(
            BracketColorPalette.baseColor(
                myFixture.editor.colorsScheme,
                PluginSettings.getInstance().state,
                pair.depth,
            ),
            activeAttributes.effectColor,
        )
        assertEquals(EffectType.BOXED, activeAttributes.effectType)

        settings.levelBaseColors[2] = 0x123456
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertTrue(
            bracketColorHighlighters().all {
                it.getTextAttributes(myFixture.editor.colorsScheme)?.foregroundColor ==
                    Color(0x123456)
            },
        )
        assertEquals(
            Color(0x123456),
            activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_COLOR_KEY),
        )
        assertEquals(
            Color(0x123456),
            activePairHighlighters().first()
                .getTextAttributes(myFixture.editor.colorsScheme)
                ?.effectColor,
        )
        assertEquals(1, collections)

        settings.colorBracketTokens = false
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertTrue(bracketColorHighlighters().isEmpty())
        val hiddenBracketRanges = ownedHighlighters().filter {
            it.getUserData(GuideLineHighlightingPass.GUIDE_KEY) == null &&
                it.getUserData(GuideLineHighlightingPass.ACTIVE_PAIR_HIGHLIGHT_KEY) != true
        }
        assertTrue(hiddenBracketRanges.isEmpty())
        assertNotNull(activeGuide())

        settings.showActiveGuide = false
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertNull(activeGuide())
        assertEquals(2, activePairHighlighters().size)

        settings.showActiveGuide = true
        settings.showActivePairBorder = false
        settings.showActivePairBackground = false
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertNotNull(activeGuide())
        assertTrue(activePairHighlighters().isEmpty())

        settings.showActivePairBackground = true
        settings.pairBackgroundOpacityPercent = 0
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertTrue(activePairHighlighters().isEmpty())

        settings.showActivePairBorder = true
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
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

        settings.showActivePairBorder = true
        settings.showActivePairBackground = true
        settings.pairBackgroundOpacityPercent =
            PluginSettings.DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT
        settings.useIndependentComponentColors = true
        settings.guideLineColors[2] = 0x224466
        settings.pairBorderColors[2] = 0x335577
        settings.pairBackgroundColors[2] = 0x446688
        settings.showVerticalGuide = false
        settings.showHorizontalGuides = true
        settings.guideLineWidth = 3
        settings.guideOpacityPercent = 65
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        val advancedAttributes = checkNotNull(
            activePairHighlighters().first().getTextAttributes(
                myFixture.editor.colorsScheme,
            ),
        )
        assertEquals(Color(0x335577), advancedAttributes.effectColor)
        assertEquals(
            BracketColorPalette.pairBackgroundColor(
                myFixture.editor.colorsScheme,
                settings,
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
            activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_RENDER_OPTIONS_KEY),
        )
        assertEquals(
            Color(0x224466),
            activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_COLOR_KEY),
        )

        settings.showHorizontalGuides = false
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertNull(activeGuide())
        assertEquals(2, activePairHighlighters().size)

        settings.enabled = false
        GuideLineHighlightingPass.refreshSettings(myFixture.editor)
        assertNull(activeGuide())
        assertTrue(bracketColorHighlighters().isEmpty())
        assertTrue(activePairHighlighters().isEmpty())
        assertEquals(1, collections)
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

        PluginSettings.getInstance().state.enabled = false
        applyPass(provider)
        assertEquals(0, collections)
        assertTrue(ownedHighlighters().isEmpty())

        PluginSettings.getInstance().state.enabled = true
        applyPass(provider)
        assertEquals(1, collections)
        assertEquals(3, ownedHighlighters().size)
    }

    fun testBulkUpdatesBothLargeCreationAndLargeStaleRemoval() {
        val pairCount = 5_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("Large.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        var bulkStarts = 0
        var bulkFinishes = 0
        editor.document.addDocumentListener(
            object : BulkAwareDocumentListener.Simple {
                override fun bulkUpdateStarting(document: Document) {
                    bulkStarts++
                }

                override fun bulkUpdateFinished(document: Document) {
                    bulkFinishes++
                }
            },
            testRootDisposable,
        )
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

        applyPass(BracketPairProvider { pairs })
        assertEquals(pairCount * 2 + 1, ownedHighlighters().size)
        assertEquals(1, ownedHighlighters().count { it.customRenderer === BracketGuideRenderer })
        assertEquals(1, bulkStarts)
        assertEquals(1, bulkFinishes)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("x".repeat(source.length))
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        applyPass(BracketPairProvider { emptyList() })

        assertTrue(ownedHighlighters().isEmpty())
        assertEquals(2, bulkStarts)
        assertEquals(2, bulkFinishes)
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

        val initialMillis = measureTimeMillis {
            applyPass(provider) { visibleRange }
        }

        assertEquals(1, collections)
        assertTrue(bracketColorHighlighters().size <= 1_024)
        assertTrue("50k-pair viewport apply took ${initialMillis}ms", initialMillis < 2_000)
        val firstViewport = bracketColorHighlighters().toSet()

        visibleRange = TextRange(50_000, 50_256)
        val scrollMillis = measureTimeMillis {
            GuideLineHighlightingPass.updateVisiblePresentation(editor)
        }

        assertEquals(1, collections)
        assertTrue(bracketColorHighlighters().size <= 1_536)
        assertTrue(firstViewport.any { !it.isValid })
        assertTrue("50k-pair viewport refresh took ${scrollMillis}ms", scrollMillis < 1_000)
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
            activeGuide()?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair?.closeOffset,
        )
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
        inReadAction {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
    }

    private fun ownedHighlighters(): List<RangeHighlighter> {
        return myFixture.editor.markupModel.allHighlighters.filter { highlighter ->
            highlighter.getUserData(GuideLineHighlightingPass.OWNED_HIGHLIGHTER_KEY) == true
        }
    }

    private fun guideHighlighters(): List<RangeHighlighter> {
        return ownedHighlighters().filter {
            it.getUserData(GuideLineHighlightingPass.GUIDE_KEY) != null
        }
    }

    private fun bracketColorHighlighters(): List<RangeHighlighter> {
        return ownedHighlighters().filter {
            it.textAttributesKey in BracketColorPalette.LEVEL_KEYS
        }
    }

    private fun activePairHighlighters(): List<RangeHighlighter> {
        return ownedHighlighters().filter {
            it.getUserData(GuideLineHighlightingPass.ACTIVE_PAIR_HIGHLIGHT_KEY) == true
        }
    }

    private fun activeGuide(): RangeHighlighter? {
        return guideHighlighters().singleOrNull {
            it.customRenderer === BracketGuideRenderer
        }
    }

    private fun <T> inReadAction(action: () -> T): T {
        return ReadAction.compute<T, RuntimeException>(action)
    }
}
