package com.sijunyang.bracketpairguides.ui.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
    private fun open() {
        myFixture.configureByText("Contract.java", "class C {\n    void f() {}\n}")
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
            BracketGuidePreferences(colorBracketTokens = false),
            EditorCapabilities.MAIN,
            EditorActivity.ACTIVE,
            NativeGuideAdvisory(),
            factory,
        )
        guide.start()
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
