package com.sijunyang.bracketpairguides.ui.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ref.GCWatcher
import com.sijunyang.bracketpairguides.model.AnalysisLimit
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.model.result.BracketView
import com.sijunyang.bracketpairguides.model.result.TokenWindow
import com.sijunyang.bracketpairguides.ui.editor.highlighting.NativeGuideAdvisory
import com.sijunyang.bracketpairguides.ui.policy.EditorActivity
import com.sijunyang.bracketpairguides.ui.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.ui.presentation.BracketColorPalette
import com.sijunyang.bracketpairguides.ui.presentation.GuideAppearance
import com.sijunyang.bracketpairguides.ui.presentation.DocumentChange
import com.sijunyang.bracketpairguides.ui.work.AnalysisUpdate
import com.sijunyang.bracketpairguides.ui.work.DisplayedGuide
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.GuideView
import com.sijunyang.bracketpairguides.ui.work.GuideWork
import com.sijunyang.bracketpairguides.ui.work.GuideWorkFactory
import com.sijunyang.bracketpairguides.ui.work.RepairUpdate
import com.sijunyang.bracketpairguides.ui.work.ViewApplication

/** Real SDK markup lifetime, exercised through the UI-owned port without runtime/core. */
class EditorGuideIdeContractTest : BasePlatformTestCase() {
    private lateinit var guide: EditorGuide
    private val demands = mutableListOf<GuideDemand>()
    private var closes = 0
    override fun tearDown() {
        try {
            if (::guide.isInitialized) guide.close()
        } finally {
            super.tearDown()
        }
    }
    private fun open(
        preferences: BracketGuidePreferences = BracketGuidePreferences(colorBracketTokens = false),
        text: String = "class C {\n    void f() {}\n}",
    ) {
        myFixture.configureByText("Contract.java", text)
        myFixture.editor.caretModel.moveToOffset(10)
        val factory = object : GuideWorkFactory {
            override fun attach(editor: Editor, view: GuideView): GuideWork = object : GuideWork {
                override fun reconcile(demand: GuideDemand) {
                    demands += demand
                }
                override fun refresh() = Unit
                override fun observeNativeGuide(candidate: DisplayedGuide) = Unit
                override fun close() {
                    closes++
                }
            }
        }
        guide = EditorGuide(
            myFixture.editor,
            preferences,
            EditorCapabilities.MAIN,
            EditorActivity.ACTIVE,
            NativeGuideAdvisory(),
            factory,
        )
        guide.start()
    }
    private fun warmPairSwitch(preferences: BracketGuidePreferences): List<BracketGuide> {
        val text = "class C {\n    void f() {\n        value();\n    }\n    void g() {\n        value();\n    }\n}"
        open(preferences, text)
        val editor = myFixture.editor
        editor.component.setSize(800, 600)
        editor.component.doLayout()
        editor.contentComponent.setSize(800, 600)
        val geometry = listOf("f()", "g()").map { method ->
            val start = text.indexOf('{', text.indexOf(method))
            val end = text.indexOf('}', start)
            val pair = BracketPair(start, 1, end, 1, 1,
                editor.document.getLineNumber(start), editor.document.getLineNumber(end))
            BracketGuide(pair, 4, pair.closeLine)
        }
        val offsets = geometry.flatMap { listOf(it.pair.openOffset, it.pair.closeOffset) }.sorted()
        val lookup = object : BracketView {
            override fun activePairAt(offset: Int): BracketPair? = geometry.firstOrNull {
                offset > it.pair.openOffset && offset < it.pair.closeOffset + it.pair.closeTokenLength
            }?.pair
            override fun guideFor(pair: BracketPair): BracketGuide = geometry.single { it.pair == pair }
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = object : TokenWindow {
                override val size = offsets.size
                override val isCapped = false
                override val stableFocusStartOffset = 0
                override val stableFocusEndOffset = text.length
                override fun offsetAt(index: Int) = offsets[index]
                override fun lengthAt(index: Int) = 1
                override fun depthAt(index: Int) = 1
            }
        }
        editor.caretModel.moveToOffset(geometry.first().pair.openOffset + 1)
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision,
            AnalysisResult.Available(lookup, demands.last().coverage, BraceMatcherAvailability.AVAILABLE))))
        return geometry
    }

    private fun paintGuide(highlighter: RangeHighlighter, renderer: BracketGuideDrawing): IntArray {
        val image = BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.clipRect(0, 0, image.width, image.height)
            renderer.paint(myFixture.editor, highlighter, graphics)
        } finally { graphics.dispose() }
        return (image.raster.dataBuffer as DataBufferInt).data.copyOf()
    }

    private fun assertCurrentPairPaint(highlighter: RangeHighlighter, expected: BracketGuide,
        preferences: BracketGuidePreferences): IntArray {
        val drawing = highlighter.customRenderer as BracketGuideDrawing
        assertEquals(expected, drawing.guide)
        val actual = paintGuide(highlighter, drawing)
        val expectedDrawing = BracketGuideDrawing(expected,
            GuideAppearance(preferences.showVerticalGuide, preferences.showHorizontalGuides,
                preferences.guideLineWidth, preferences.guideOpacityPercent),
            BracketColorPalette.guideLineColor(preferences, expected.pair.depth))
        assertTrue("Actual SDK-coordinate rendering must paint guide pixels", actual.any { it != 0 })
        assertTrue("Current renderer must paint the new geometry, not the old pair",
            actual.contentEquals(paintGuide(highlighter, expectedDrawing)))
        val endpoints = myFixture.editor.markupModel.allHighlighters
            .filter { it.layer == HighlighterLayer.ELEMENT_UNDER_CARET }
            .sortedBy { it.startOffset }
        assertEquals(listOf(expected.pair.openOffset, expected.pair.closeOffset), endpoints.map { it.startOffset })
        assertEquals(listOf(expected.pair.openOffset + 1, expected.pair.closeOffset + 1), endpoints.map { it.endOffset })
        assertTrue(endpoints.all { it.isValid })
        return actual
    }

    fun testWarmPairSwitchImmediatelyReusesGuideRendererAndPaintsCurrentGeometry() {
        val preferences = BracketGuidePreferences(colorBracketTokens = true, showActivePairBorder = true)
        val geometry = warmPairSwitch(preferences)
        val editor = myFixture.editor
        val original = editor.markupModel.allHighlighters.single { it.customRenderer is BracketGuideDrawing }
        val renderer = original.customRenderer
        val tokens = editor.markupModel.allHighlighters.filter {
            it !== original && it.layer != HighlighterLayer.ELEMENT_UNDER_CARET
        }
        assertTrue("Enabled token presentation must have real SDK resources", tokens.isNotEmpty())
        val firstPixels = assertCurrentPairPaint(original, geometry.first(), preferences)
        for (expected in listOf(geometry.last(), geometry.first())) {
            editor.caretModel.moveToOffset(expected.pair.openOffset + 1)
            // This fixture has no EditorGuideEvents adapter; invoke the actual UI entry once.
            guide.caretMoved()
            val current = editor.markupModel.allHighlighters.single { it.customRenderer is BracketGuideDrawing }
            assertSame(original, current)
            assertSame(renderer, current.customRenderer)
            assertTrue(original.isValid)
            val pixels = assertCurrentPairPaint(current, expected, preferences)
            if (expected == geometry.last()) assertFalse(firstPixels.contentEquals(pixels))
            assertTrue(tokens.all { token -> token.isValid && editor.markupModel.allHighlighters.any { it === token } })
            assertNull(demands.last().repair)
        }
    }

    fun testFreshNestedPairTransitionKeepsGuideRendererAndFreshEndpointAuthority() {
        val preferences = BracketGuidePreferences(colorBracketTokens = true, showActivePairBorder = true)
        val geometry = warmPairSwitch(preferences)
        val editor = myFixture.editor
        val original = editor.markupModel.allHighlighters.single { it.customRenderer is BracketGuideDrawing }
        val renderer = original.customRenderer
        var reentered = false
        afterFirstAdded {
            reentered = true
            editor.caretModel.moveToOffset(geometry.first().pair.openOffset + 1)
            guide.caretMoved()
        }
        editor.caretModel.moveToOffset(geometry.last().pair.openOffset + 1)
        guide.caretMoved()
        assertTrue(reentered)
        val current = editor.markupModel.allHighlighters.single { it.customRenderer is BracketGuideDrawing }
        assertSame(original, current)
        assertSame(renderer, current.customRenderer)
        assertCurrentPairPaint(current, geometry.first(), preferences)
        assertNull(demands.last().repair)
    }

    fun testAffectedEditHidesGuideBeforeSingleCompleteContentDemand() {
        open()
        val document = myFixture.editor.document
        val pair = BracketPair(8, 1, document.textLength - 1, 1, 0, 0, 2)
        val result = AnalysisResult.Available(
            object : BracketView {
                override fun activePairAt(offset: Int) = pair
                override fun guideFor(pair: BracketPair) = BracketGuide(pair, 0)
                override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow =
                    error("tokens disabled")
            },
            demands.last().coverage,
            BraceMatcherAvailability.AVAILABLE,
        )
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, result)))
        assertTrue(myFixture.editor.markupModel.allHighlighters.any { it.customRenderer is BracketGuideDrawing })
        val before = demands.size
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(10, " ") }
        guide.documentChanged(DocumentChange(10, 0, 1))
        assertFalse(myFixture.editor.markupModel.allHighlighters.any { it.customRenderer is BracketGuideDrawing })
        assertEquals(before + 1, demands.size)
        assertEquals(GuideChange.CONTENT, demands.last().change)
        assertNotNull(demands.last().repair)
        assertTrue(demands.last().repair!!.exact)
    }
    fun testCloseRevokesViewAndIsIdempotent() {
        open()
        guide.close()
        guide.close()
        assertEquals(1, closes)
        val result = AnalysisResult.Unavailable(demands.last().coverage, AnalysisLimit.PAIR_CAPACITY)
        assertEquals(ViewApplication.OBSOLETE, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, result)))
        val before = demands.size
        guide.caretMoved()
        assertEquals(before, demands.size)
    }
    fun testLimitedFullResultPreservesSuccessfulCurrentRepairGeometry() {
        open()
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        val lookup = object : BracketView {
            override fun activePairAt(offset: Int) = pair
            override fun guideFor(pair: BracketPair): BracketGuide? = null
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow =
                error("tokens disabled")
        }
        val available = AnalysisResult.Available(lookup, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available))
        assertNotNull(demands.last().repair)
        guide.caretMoved()
        assertNotNull(demands.last().repair)
        val repaired = BracketGuide(pair, 2, 1)
        assertEquals(ViewApplication.APPLIED, guide.applyRepair(RepairUpdate(demands.last().guideRevision, repaired)))
        val limited = available.copy(
            coverage = available.coverage.withoutGuidePosition(),
            limit = AnalysisLimit.GUIDE_CAPACITY,
        )
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, limited)))
        val drawings = myFixture.editor.markupModel.allHighlighters.mapNotNull {
            it.customRenderer as? BracketGuideDrawing
        }
        assertEquals(listOf(repaired), drawings.map { it.guide })
    }
    fun testUnchangedCaretCallbackPreservesAllActualMarkupWithoutSdkCallbacks() {
        open(BracketGuidePreferences(showActivePairBorder = true))
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        var guideQueries = 0
        val result = AnalysisResult.Available(object : BracketView {
            override fun activePairAt(offset: Int) = pair
            override fun guideFor(pair: BracketPair): BracketGuide { guideQueries++; return BracketGuide(pair, 0) }
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = object : TokenWindow {
                override val size = 2
                override val isCapped = false
                override val stableFocusStartOffset = 0
                override val stableFocusEndOffset = myFixture.editor.document.textLength
                override fun offsetAt(index: Int) = if (index == 0) pair.openOffset else pair.closeOffset
                override fun lengthAt(index: Int) = 1
                override fun depthAt(index: Int) = pair.depth
            }
        }, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, result)))
        val before = myFixture.editor.markupModel.allHighlighters.toList()
        assertEquals(5, before.size)
        fun documentMarkers(): List<RangeMarker> {
            val markers = mutableListOf<RangeMarker>()
            (myFixture.editor.document as DocumentEx).processRangeMarkers { marker ->
                markers += marker
                true
            }
            return markers
        }
        val beforeMarkers = documentMarkers()
        var callbacks = 0
        (myFixture.editor.markupModel as MarkupModelEx).addMarkupModelListener(testRootDisposable, object : MarkupModelListener {
            override fun afterAdded(highlighter: RangeHighlighterEx) { callbacks++ }
            override fun beforeRemoved(highlighter: RangeHighlighterEx) { callbacks++ }
            override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean) { callbacks++ }
        })
        val previousRevision = demands.last().guideRevision
        assertEquals(1, guideQueries)
        guide.caretMoved()
        assertEquals(1, guideQueries)
        assertEquals(previousRevision + 1, demands.last().guideRevision)
        val after = myFixture.editor.markupModel.allHighlighters.toList()
        assertEquals(before.size, after.size)
        assertTrue(before.all { previous -> previous.isValid && after.any { it === previous } })
        assertEquals(0, callbacks)
        val afterMarkers = documentMarkers()
        assertEquals(beforeMarkers.size, afterMarkers.size)
        assertTrue(beforeMarkers.all { previous -> previous.isValid && afterMarkers.any { it === previous } })
    }

    fun testEndpointStyleThemeAndPairChangesStillReplaceIncompatibleMarkup() {
        var options = BracketGuidePreferences(colorBracketTokens = false, showActivePairBorder = true, showActivePairBackground = true)
        open(options)
        val editor = myFixture.editor
        val pair = BracketPair(8, 1, editor.document.textLength - 1, 1, 0, 0, 2)
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, BracketGuide(pair, 0)))))
        fun endpoints(): List<RangeHighlighter> = editor.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.ELEMENT_UNDER_CARET }
        val original = endpoints()
        val originalColors = original.map { it.getTextAttributes(editor.colorsScheme)?.effectColor }
        options = options.copy(useIndependentComponentColors = true, pairBorderColors = List(6) { 0xFF1122 })
        guide.updateOptions(options, false)
        val recolored = endpoints()
        assertEquals(2, recolored.size)
        assertTrue(original.all { !it.isValid })
        assertTrue(recolored.map { it.getTextAttributes(editor.colorsScheme)?.effectColor } != originalColors)

        val previousBackgrounds = recolored.map { it.getTextAttributes(editor.colorsScheme)?.backgroundColor }
        // The editor delegates its scheme and does not support cloning; clone the actual global scheme.
        val previousEditorBackground = editor.colorsScheme.defaultBackground
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        val text = scheme.getAttributes(HighlighterColors.TEXT).clone()
        text.backgroundColor = if (previousEditorBackground == Color.BLACK) Color.WHITE else Color.BLACK
        scheme.setAttributes(HighlighterColors.TEXT, text)
        (editor as EditorEx).setColorsScheme(scheme)
        guide.updateOptions(options, true)
        val themed = endpoints()
        assertEquals(2, themed.size)
        assertTrue(recolored.all { !it.isValid })
        assertTrue(themed.map { it.getTextAttributes(editor.colorsScheme)?.backgroundColor } != previousBackgrounds)

        val innerOpen = editor.document.text.indexOf('{', pair.openOffset + 1)
        val nextPair = BracketPair(innerOpen, 1, innerOpen + 1, 1, 1, 1, 1)
        editor.caretModel.moveToOffset(innerOpen + 1)
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(nextPair, BracketGuide(nextPair, 0)))))
        val moved = endpoints()
        assertTrue(themed.all { !it.isValid })
        assertEquals(setOf(nextPair.openOffset, nextPair.closeOffset), moved.map { it.startOffset }.toSet())
        assertTrue(moved.all { it.endOffset - it.startOffset == 1 })
    }

    fun testUnchangedAdoptionRejectsDisposedEndpointAndExternalStyleMutation() {
        open(BracketGuidePreferences(colorBracketTokens = false, showActivePairBorder = true))
        val editor = myFixture.editor
        val pair = BracketPair(8, 1, editor.document.textLength - 1, 1, 0, 0, 2)
        var queries = 0
        val view = object : BracketView {
            override fun activePairAt(offset: Int) = pair
            override fun guideFor(pair: BracketPair): BracketGuide { queries++; return BracketGuide(pair, 0) }
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = error("tokens disabled")
        }
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision,
            AnalysisResult.Available(view, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)))
        fun endpoints() = editor.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.ELEMENT_UNDER_CARET }
        val first = endpoints().first()
        first.dispose()
        guide.caretMoved()
        assertEquals(2, queries)
        assertEquals(2, endpoints().size)
        assertTrue(endpoints().all { it.isValid })
        val changed = endpoints().first()
        val expectedColor = changed.getTextAttributes(editor.colorsScheme)!!.effectColor
        val attributes = changed.getTextAttributes(editor.colorsScheme)!!.clone()
        attributes.effectColor = if (expectedColor == Color.RED) Color.BLUE else Color.RED
        (changed as RangeHighlighterEx).setTextAttributes(attributes)
        guide.caretMoved()
        assertEquals(3, queries)
        assertFalse(changed.isValid)
        assertEquals(listOf(expectedColor, expectedColor), endpoints().map { it.getTextAttributes(editor.colorsScheme)!!.effectColor })
        val extraEffectEndpoint = endpoints().first()
        val expectedAttributes = extraEffectEndpoint.getTextAttributes(editor.colorsScheme)!!.clone()
        val extraAttributes = expectedAttributes.clone()
        extraAttributes.setAdditionalEffects(mapOf(EffectType.WAVE_UNDERSCORE to Color.RED))
        (extraEffectEndpoint as RangeHighlighterEx).setTextAttributes(extraAttributes)
        guide.caretMoved()
        assertEquals(4, queries)
        assertFalse(extraEffectEndpoint.isValid)
        for (endpoint in endpoints()) {
            assertEquals(expectedAttributes, endpoint.getTextAttributes(editor.colorsScheme))
        }
    }

    fun testUnchangedAdoptionRejectsDisposedTrackedPairMarker() {
        open(BracketGuidePreferences(colorBracketTokens = false, showActivePairBorder = true))
        val editor = myFixture.editor
        val pair = BracketPair(8, 1, editor.document.textLength - 1, 1, 0, 0, 2)
        var queries = 0
        val view = object : BracketView {
            override fun activePairAt(offset: Int) = pair
            override fun guideFor(pair: BracketPair): BracketGuide { queries++; return BracketGuide(pair, 0) }
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = error("tokens disabled")
        }
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision,
            AnalysisResult.Available(view, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)))
        val tracked = mutableListOf<RangeMarker>()
        (editor.document as DocumentEx).processRangeMarkers { marker ->
            if (marker.startOffset == pair.openOffset && marker.endOffset == pair.closeOffset + pair.closeTokenLength)
                tracked += marker
            true
        }
        assertEquals(1, tracked.size)
        tracked.single().dispose()
        guide.caretMoved()
        assertEquals(2, queries)
        val replacements = mutableListOf<RangeMarker>()
        (editor.document as DocumentEx).processRangeMarkers { marker ->
            if (marker.startOffset == pair.openOffset && marker.endOffset == pair.closeOffset + pair.closeTokenLength)
                replacements += marker
            true
        }
        assertEquals(1, replacements.size)
        assertTrue(replacements.single().isValid)
        assertNotSame(tracked.single(), replacements.single())
    }

    fun testFreshResultDuringUnchangedCaretQueryKeepsNewFrameAuthority() {
        open(BracketGuidePreferences(colorBracketTokens = false, showActivePairBorder = true))
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        var reenter = false
        val fresh = BracketGuide(pair, 3, 1)
        val oldView = object : BracketView {
            override fun activePairAt(offset: Int): BracketPair {
                if (reenter) {
                    reenter = false
                    assertEquals(ViewApplication.APPLIED,
                        guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, fresh))))
                }
                return pair
            }
            override fun guideFor(pair: BracketPair) = BracketGuide(pair, 0)
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = error("tokens disabled")
        }
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision,
            AnalysisResult.Available(oldView, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)))
        reenter = true
        guide.caretMoved()
        val drawing = myFixture.editor.markupModel.allHighlighters.mapNotNull { it.customRenderer as? BracketGuideDrawing }.single()
        assertEquals(fresh, drawing.guide)
        assertEquals(3, myFixture.editor.markupModel.allHighlighters.size)
        assertNull(demands.last().repair)
        guide.caretMoved()
        assertEquals(fresh, drawing.guide)
    }

    private fun installTrackedLookup(): GCWatcher {
        val lookup = object : BracketView {
            override fun activePairAt(offset: Int): BracketPair? = null
            override fun guideFor(pair: BracketPair): BracketGuide? = null
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = error("tokens disabled")
        }
        val result = AnalysisResult.Available(lookup, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, result)))
        return GCWatcher.tracking(lookup)
    }
    fun testHiddenPresentationReleasesReadOnlyLookup() {
        open()
        val watcher = installTrackedLookup()
        guide.updateSurface(EditorCapabilities.MAIN, EditorActivity.INACTIVE)
        watcher.ensureCollected()
        assertEmpty(myFixture.editor.markupModel.allHighlighters.toList())
    }
    fun testDisabledPresentationReleasesReadOnlyLookup() {
        open()
        val watcher = installTrackedLookup()
        guide.updateOptions(BracketGuidePreferences(enabled = false), false)
        watcher.ensureCollected()
        assertEmpty(myFixture.editor.markupModel.allHighlighters.toList())
    }
    private fun available(pair: BracketPair, geometry: BracketGuide?): AnalysisResult.Available =
        AnalysisResult.Available(object : BracketView {
            override fun activePairAt(offset: Int) = pair
            override fun guideFor(pair: BracketPair) = geometry
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = error("tokens disabled")
        }, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)

    private fun afterFirstAdded(action: () -> Unit) {
        var entered = false
        (myFixture.editor.markupModel as MarkupModelEx).addMarkupModelListener(testRootDisposable, object : MarkupModelListener {
            override fun afterAdded(highlighter: RangeHighlighterEx) {
                if (!entered) { entered = true; action() }
            }
        })
    }

    fun testCloseInsideActualRepairMarkupCallbackLeavesNoOrphanHighlighter() {
        open()
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, null)))
        var entered = false
        afterFirstAdded { entered = true; guide.close() }
        assertEquals(ViewApplication.OBSOLETE, guide.applyRepair(RepairUpdate(demands.last().guideRevision, BracketGuide(pair, 2, 1))))
        assertTrue(entered)
        assertEmpty(myFixture.editor.markupModel.allHighlighters.toList())
    }

    fun testFreshResultInsideRepairMarkupCallbackOwnsItsResourcesAfterOldRollback() {
        open()
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, null)))
        val fresh = BracketGuide(pair, 3, 1)
        var applied: ViewApplication? = null
        afterFirstAdded { applied = guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, fresh))) }
        assertEquals(ViewApplication.OBSOLETE, guide.applyRepair(RepairUpdate(demands.last().guideRevision, BracketGuide(pair, 2, 1))))
        assertEquals(ViewApplication.APPLIED, applied)
        val marks = myFixture.editor.markupModel.allHighlighters.toList()
        assertEquals(1, marks.size)
        assertEquals(fresh, (marks.single().customRenderer as BracketGuideDrawing).guide)
        assertNull(demands.last().repair)
    }

    fun testFailureAfterFreshReentrantApplyPropagatesWithoutClearingFreshMarkup() {
        open()
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, null)))
        val fresh = BracketGuide(pair, 3, 1)
        val expected = IllegalStateException("old SDK effect failed after fresh apply")
        val old = AnalysisResult.Available(object : BracketView {
            override fun activePairAt(offset: Int): BracketPair {
                assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, available(pair, fresh))))
                throw expected
            }
            override fun guideFor(pair: BracketPair): BracketGuide? = null
            override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = error("tokens disabled")
        }, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)
        try {
            guide.applyAnalysis(AnalysisUpdate(demands.last().revision, old))
            fail("render query failure must propagate")
        } catch (actual: IllegalStateException) { assertSame(expected, actual) }
        val marks = myFixture.editor.markupModel.allHighlighters.toList()
        assertEquals(1, marks.size)
        assertEquals(fresh, (marks.single().customRenderer as BracketGuideDrawing).guide)
    }

    private fun tokenResult(offset: Int): AnalysisResult.Available = AnalysisResult.Available(object : BracketView {
        override fun activePairAt(offset: Int): BracketPair? = null
        override fun guideFor(pair: BracketPair): BracketGuide? = null
        override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow = object : TokenWindow {
            override val size = 1
            override val isCapped = false
            override val stableFocusStartOffset = 0
            override val stableFocusEndOffset = myFixture.editor.document.textLength
            override fun offsetAt(index: Int) = offset
            override fun lengthAt(index: Int) = 1
            override fun depthAt(index: Int) = 0
        }
    }, demands.last().coverage, BraceMatcherAvailability.AVAILABLE)

    fun testFreshTokenResultInsideMarkupCallbackSurvivesOldTokenRollback() {
        open(BracketGuidePreferences(colorBracketTokens = true, showActiveGuide = false))
        var applied: ViewApplication? = null
        afterFirstAdded { applied = guide.applyAnalysis(AnalysisUpdate(demands.last().revision, tokenResult(22))) }
        assertEquals(ViewApplication.OBSOLETE, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, tokenResult(8))))
        assertEquals(ViewApplication.APPLIED, applied)
        assertEquals(listOf(22), myFixture.editor.markupModel.allHighlighters.map { it.startOffset })
    }

    fun testCloseInsideBeforeRemovedRevokesOldTokenRenderingAndLeavesNoMarkup() {
        open(BracketGuidePreferences(colorBracketTokens = true, showActiveGuide = false))
        assertEquals(ViewApplication.APPLIED, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, tokenResult(8))))
        val old = myFixture.editor.markupModel.allHighlighters.single()
        var entered = false
        (myFixture.editor.markupModel as MarkupModelEx).addMarkupModelListener(testRootDisposable, object : MarkupModelListener {
            override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                if (!entered && highlighter === old) { entered = true; guide.close() }
            }
        })
        assertEquals(ViewApplication.OBSOLETE, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, tokenResult(22))))
        assertTrue(entered)
        assertFalse(old.isValid)
        assertEmpty(myFixture.editor.markupModel.allHighlighters.toList())
    }

    fun testReentrantCloseDuringResultQueryLeavesNoMarkup() {
        open()
        val document = myFixture.editor.document
        val pair = BracketPair(8, 1, document.textLength - 1, 1, 0, 0, 2)
        val result = AnalysisResult.Available(
            object : BracketView {
                override fun activePairAt(offset: Int): BracketPair {
                    guide.close()
                    return pair
                }
                override fun guideFor(pair: BracketPair) = BracketGuide(pair, 0)
                override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow =
                    error("tokens disabled")
            },
            demands.last().coverage,
            BraceMatcherAvailability.AVAILABLE,
        )
        assertEquals(ViewApplication.OBSOLETE, guide.applyAnalysis(AnalysisUpdate(demands.last().revision, result)))
        assertEmpty(myFixture.editor.markupModel.allHighlighters.toList())
    }
    fun testRenderingFailurePropagatesAndLeavesNoMarkup() {
        open()
        val expected = IllegalStateException("semantic query failed")
        val result = AnalysisResult.Available(
            object : BracketView {
                override fun activePairAt(offset: Int): BracketPair? = throw expected
                override fun guideFor(pair: BracketPair): BracketGuide? = null
                override fun visibleTokens(range: OffsetRange, focus: Int, maximum: Int): TokenWindow =
                    error("tokens disabled")
            },
            demands.last().coverage,
            BraceMatcherAvailability.AVAILABLE,
        )
        try {
            guide.applyAnalysis(AnalysisUpdate(demands.last().revision, result))
            fail("render failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
        assertEmpty(myFixture.editor.markupModel.allHighlighters.toList())
    }
}
