package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pipeline.IndexLayout
import com.sijunyang.bracketpairguides.analysis.pipeline.TokenStorage
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DocumentBracketIndexesTest : BasePlatformTestCase() {
    fun testAnalysisSharesIndexesAcrossSplitEditorsButKeepsViewStateSeparate() {
        val source = """
            class SplitView {
                void run() {
                    call();
                }
            }
        """.trimIndent()
        myFixture.configureByText("SplitView.java", source)
        val firstEditor = myFixture.editor
        val secondEditor = EditorFactory.getInstance().createEditor(
            firstEditor.document,
            project,
            myFixture.file.fileType,
            false,
        )
        try {
            val coverage = AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = true,
            )
            val analysis = BracketAnalysis()

            val first = complete(inReadAction {
                analysis.analyze(input(firstEditor, coverage), EmptyProgressIndicator())
            }) as IndexedBracketSnapshot
            val second = complete(inReadAction {
                analysis.analyze(input(secondEditor, coverage), EmptyProgressIndicator())
            }) as IndexedBracketSnapshot

            assertNotSame(first, second)
            assertNotSame(first.stamp, second.stamp)
            assertSame(first.indexes, second.indexes)

            val caretOffset = source.indexOf("call") + 1
            val firstPair = checkNotNull(first.activePairAt(caretOffset))
            val secondPair = checkNotNull(second.activePairAt(caretOffset))
            assertNotSame(firstPair, secondPair)
            assertSame(firstPair, first.activePairAt(caretOffset + 1))
            assertSame(secondPair, second.activePairAt(caretOffset + 1))
        } finally {
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testPairHashCollisionCannotCanonicalizeDifferentGeometry() {
        myFixture.configureByText("Collision.java", "class Collision { }")
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = true,
            guidePosition = false,
        )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val firstPairs = pairTable(openLine = 0, closeLine = 100)
        val collidingPairs = pairTable(openLine = 1, closeLine = 69)
        assertEquals(firstPairs.contentHash(), collidingPairs.contentHash())
        assertFalse(firstPairs.hasSameContent(collidingPairs, NO_CANCELLATION_PROBE))

        val indexesByDocument = DocumentBracketIndexes()
        val firstIndexes = indexes(firstPairs)
        val secondIndexes = indexes(collidingPairs)

        assertSame(
            firstIndexes,
            indexesByDocument.canonical(input, layout, firstPairs, firstIndexes),
        )
        assertSame(
            secondIndexes,
            indexesByDocument.canonical(input, layout, collidingPairs, secondIndexes),
        )
        assertNotSame(firstIndexes, secondIndexes)
    }

    fun testTokenOnlyIndexesShareByExactObservableContentWithoutRetainingPairGeometry() {
        myFixture.configureByText("TokenOnly.java", "class TokenOnly { value }")
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val firstPairs = pairTable(
            intArrayOf(0, 10, 0),
            intArrayOf(2, 8, 0),
        )
        val sameTokensWithDifferentPairs = pairTable(
            intArrayOf(0, 8, 0),
            intArrayOf(2, 10, 0),
        )
        assertFalse(
            firstPairs.hasSameContent(
                sameTokensWithDifferentPairs,
                NO_CANCELLATION_PROBE,
            ),
        )
        val indexesByDocument = DocumentBracketIndexes()
        val firstIndexes = detachedIndexes(firstPairs)

        indexesByDocument.canonical(input, layout, firstPairs, firstIndexes)

        assertSame(
            firstIndexes,
            indexesByDocument.canonical(
                input,
                layout,
                sameTokensWithDifferentPairs,
                detachedIndexes(sameTokensWithDifferentPairs),
            ),
        )
        assertTrue(firstIndexes.pairs.isEmpty)
    }

    fun testRevisionLayoutAndCoverageAreExactCanonicalBoundaries() {
        myFixture.configureByText("Boundaries.java", "class Boundaries { }")
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = true,
            guidePosition = false,
        )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val pairs = pairTable(openLine = 0, closeLine = 0)
        val indexesByDocument = DocumentBracketIndexes()
        val firstIndexes = indexes(pairs)
        assertSame(
            firstIndexes,
            indexesByDocument.canonical(input, layout, pairs, firstIndexes),
        )

        val otherLayoutIndexes = indexes(pairs)
        assertSame(
            otherLayoutIndexes,
            indexesByDocument.canonical(
                input,
                layout.copy(tokenStorage = TokenStorage.NONE),
                pairs,
                otherLayoutIndexes,
            ),
        )

        val otherCoverage = coverage.copy(tokens = false)
        val otherCoverageIndexes = indexes(pairs)
        assertSame(
            otherCoverageIndexes,
            indexesByDocument.canonical(
                input(myFixture.editor, otherCoverage),
                layout,
                pairs,
                otherCoverageIndexes,
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        val otherRevisionIndexes = indexes(pairs)
        assertSame(
            otherRevisionIndexes,
            indexesByDocument.canonical(
                input(myFixture.editor, coverage),
                layout,
                pairs,
                otherRevisionIndexes,
            ),
        )
    }

    fun testTabSizeIsCanonicalBoundaryOnlyForGuideIndexes() {
        myFixture.configureByText("Tabs.java", "class Tabs { }")
        val editor = myFixture.editor
        val pairs = pairTable(openLine = 0, closeLine = 0)
        val guideCoverage = AnalysisCoverage(
            tokens = true,
            activePair = true,
            guidePosition = true,
        )
        val tokenCoverage = guideCoverage.copy(guidePosition = false)
        val guideLayout = IndexLayout.forCoverage(guideCoverage)
        val tokenLayout = IndexLayout.forCoverage(tokenCoverage)
        val guideIndexesByDocument = DocumentBracketIndexes()
        val tokenIndexesByDocument = DocumentBracketIndexes()
        val originalTabSize = editor.settings.getTabSize(project)
        val guideIndexes = indexes(
            pairs,
            guidePositions = guidePositions(originalTabSize),
        )
        val tokenIndexes = indexes(pairs)

        try {
            guideIndexesByDocument.canonical(
                input(editor, guideCoverage),
                guideLayout,
                pairs,
                guideIndexes,
            )
            tokenIndexesByDocument.canonical(
                input(editor, tokenCoverage),
                tokenLayout,
                pairs,
                tokenIndexes,
            )

            editor.settings.setTabSize(originalTabSize + 1)

            val otherGuideIndexes = indexes(
                pairs,
                guidePositions = guidePositions(originalTabSize + 1),
            )
            assertSame(
                otherGuideIndexes,
                guideIndexesByDocument.canonical(
                    input(editor, guideCoverage),
                    guideLayout,
                    pairs,
                    otherGuideIndexes,
                ),
            )
            assertSame(
                tokenIndexes,
                tokenIndexesByDocument.canonical(
                    input(editor, tokenCoverage),
                    tokenLayout,
                    pairs,
                    indexes(pairs),
                ),
            )
        } finally {
            editor.settings.setTabSize(originalTabSize)
        }
    }

    fun testCanonicalizationIsCancellableAndDoesNotSerializeUnrelatedDocuments() {
        myFixture.configureByText("First.java", "class First { value }")
        val firstEditor = myFixture.editor
        val secondDocument = EditorFactory.getInstance().createDocument(
            "class Second { value }",
        )
        val secondEditor = EditorFactory.getInstance().createEditor(
            secondDocument,
            project,
            myFixture.file.fileType,
            false,
        )
        val executor = Executors.newFixedThreadPool(3)
        val comparisonEntered = CountDownLatch(1)
        val releaseComparison = CountDownLatch(1)
        try {
            val coverage = AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = false,
            )
            val layout = IndexLayout.forCoverage(coverage)
            val firstInput = input(firstEditor, coverage)
            val secondInput = input(secondEditor, coverage)
            val firstPairs = pairTable(openLine = 0, closeLine = 0)
            val secondPairs = pairTable(openLine = 0, closeLine = 0)
            val indexesByDocument = DocumentBracketIndexes()
            indexesByDocument.canonical(
                firstInput,
                layout,
                firstPairs,
                indexes(firstPairs),
            )

            val blockedComparison = executor.submit<BracketIndexes> {
                var checks = 0
                indexesByDocument.canonical(
                    input = firstInput,
                    layout = layout,
                    pairs = firstPairs,
                    candidate = indexes(firstPairs),
                    checkCanceled = {
                        if (++checks == 2) {
                            comparisonEntered.countDown()
                            check(releaseComparison.await(5, TimeUnit.SECONDS))
                        }
                    },
                )
            }
            assertTrue(comparisonEntered.await(1, TimeUnit.SECONDS))

            val secondIndexes = indexes(secondPairs)
            val unrelatedDocument = executor.submit<BracketIndexes> {
                indexesByDocument.canonical(
                    input = secondInput,
                    layout = layout,
                    pairs = secondPairs,
                    candidate = secondIndexes,
                    checkCanceled = {},
                )
            }
            assertSame(secondIndexes, unrelatedDocument.get(1, TimeUnit.SECONDS))

            val waitChecks = AtomicInteger()
            val canceledWait = executor.submit<BracketIndexes> {
                indexesByDocument.canonical(
                    input = firstInput,
                    layout = layout,
                    pairs = firstPairs,
                    candidate = indexes(firstPairs),
                    checkCanceled = {
                        if (waitChecks.incrementAndGet() == 2) {
                            throw TestCancellation()
                        }
                    },
                )
            }
            var failure: ExecutionException? = null
            try {
                canceledWait.get(1, TimeUnit.SECONDS)
                fail("Expected a canceled wait")
            } catch (expected: ExecutionException) {
                failure = expected
            }
            assertTrue(failure?.cause is TestCancellation)

            releaseComparison.countDown()
            assertNotNull(blockedComparison.get(1, TimeUnit.SECONDS))
        } finally {
            releaseComparison.countDown()
            executor.shutdownNow()
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    private fun input(editor: Editor, coverage: AnalysisCoverage): AnalysisInput =
        AnalysisInput(
            editor = editor,
            fileType = myFixture.file.fileType,
            coverage = coverage,
            disabledLanguageIds = emptySet(),
        )

    private fun indexes(
        pairs: PairTable,
        guidePositions: GuidePositionIndex? = null,
    ): BracketIndexes = BracketIndexes(
        pairs = pairs,
        tokens = BracketTokenIndex.build(pairs, NO_CANCELLATION),
        activePairs = ActiveBracketPairIndex.build(pairs, NO_CANCELLATION),
        guidePositions = guidePositions,
    )

    private fun detachedIndexes(pairs: PairTable): BracketIndexes = BracketIndexes(
        pairs = PairTable.empty(),
        tokens = BracketTokenIndex.buildDetached(pairs, NO_CANCELLATION),
        activePairs = ActiveBracketPairIndex.build(
            PairTable.empty(),
            NO_CANCELLATION,
        ),
        guidePositions = null,
    )

    private fun guidePositions(tabSize: Int): GuidePositionIndex = checkNotNull(
        GuidePositionIndex.from(
            document = myFixture.editor.document,
            tabSize = tabSize,
            progress = EmptyProgressIndicator(),
            indexedLineRange = 0 until myFixture.editor.document.lineCount,
        ),
    )

    private fun pairTable(openLine: Int, closeLine: Int): PairTable {
        val draft = PairTable.draft()
        draft.accept(
            0,
            1,
            10,
            1,
            0,
            openLine,
            closeLine,
        )
        return draft.freeze()
    }

    private fun pairTable(vararg geometry: IntArray): PairTable {
        val draft = PairTable.draft()
        geometry.forEach { pair ->
            draft.accept(
                pair[0],
                1,
                pair[1],
                1,
                pair[2],
                0,
                0,
            )
        }
        return draft.freeze()
    }

    private fun complete(outcome: AnalysisOutcome): BracketSnapshot {
        assertTrue(outcome is AnalysisOutcome.Complete)
        return (outcome as AnalysisOutcome.Complete).snapshot
    }

    private fun <T> inReadAction(action: () -> T): T =
        ReadAction.compute<T, RuntimeException>(action)

    private fun DocumentBracketIndexes.canonical(
        input: AnalysisInput,
        layout: IndexLayout,
        pairs: PairTable,
        candidate: BracketIndexes,
    ): BracketIndexes = canonical(
        input = input,
        layout = layout,
        pairs = pairs,
        candidate = candidate,
        checkCanceled = NO_CANCELLATION,
    )

    private class TestCancellation : RuntimeException()

    private companion object {
        val NO_CANCELLATION: () -> Unit = {}
        val NO_CANCELLATION_PROBE = CancellationProbe {}
    }
}
