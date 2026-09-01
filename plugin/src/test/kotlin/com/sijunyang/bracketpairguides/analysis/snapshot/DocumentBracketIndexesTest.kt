package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.intellij.DocumentGuidePositions
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DocumentBracketIndexesTest : BasePlatformTestCase() {
    fun testSplitEditorsKeepSnapshotAndPairMemoizationSeparate() {
        val source =
            """
            class SplitView {
                void run() {
                    call();
                }
            }
            """.trimIndent()
        myFixture.configureByText("SplitView.java", source)
        val firstEditor = myFixture.editor
        val secondEditor =
            EditorFactory.getInstance().createEditor(
                firstEditor.document,
                project,
                myFixture.file.fileType,
                false,
            )
        try {
            val coverage =
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = true,
                )
            val analysis = BracketAnalysis()

            val first =
                complete(
                    inReadAction {
                        analysis.analyze(input(firstEditor, coverage), EmptyProgressIndicator())
                    },
                )
            val second =
                complete(
                    inReadAction {
                        analysis.analyze(input(secondEditor, coverage), EmptyProgressIndicator())
                    },
                )

            assertThat(second).isNotSameAs(first)
            assertThat(second.stamp).isNotSameAs(first.stamp)

            val caretOffset = source.indexOf("call") + 1
            val firstPair = checkNotNull(first.activePairAt(caretOffset))
            val secondPair = checkNotNull(second.activePairAt(caretOffset))
            assertThat(secondPair).isNotSameAs(firstPair)
            assertThat(first.activePairAt(caretOffset + 1)).isSameAs(firstPair)
            assertThat(second.activePairAt(caretOffset + 1)).isSameAs(secondPair)
        } finally {
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testEquivalentIndexCandidatesShareTheCanonicalInstance() {
        myFixture.configureByText("Equivalent.java", "class Equivalent { }")
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = false,
            )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val firstPairs = pairTable(openLine = 0, closeLine = 0)
        val equivalentPairs = pairTable(openLine = 0, closeLine = 0)
        val firstIndexes = indexes(firstPairs)
        val otherIndexes = indexes(equivalentPairs)
        val indexesByDocument = DocumentBracketIndexes()

        assertThat(
            indexesByDocument.canonical(input, layout, firstPairs, firstIndexes),
        ).isSameAs(firstIndexes)
        assertThat(
            indexesByDocument.canonical(input, layout, equivalentPairs, otherIndexes),
        ).isSameAs(firstIndexes)
    }

    fun testPairHashCollisionCannotCanonicalizeDifferentGeometry() {
        myFixture.configureByText("Collision.java", "class Collision { }")
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = false,
            )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val firstPairs = pairTable(openLine = 0, closeLine = 100)
        val collidingPairs = pairTable(openLine = 1, closeLine = 69)
        assertThat(collidingPairs.contentHash()).isEqualTo(firstPairs.contentHash())
        assertThat(firstPairs.hasSameContent(collidingPairs, NO_CANCELLATION_PROBE)).isFalse()

        val indexesByDocument = DocumentBracketIndexes()
        val firstIndexes = indexes(firstPairs)
        val secondIndexes = indexes(collidingPairs)

        assertThat(
            indexesByDocument.canonical(input, layout, firstPairs, firstIndexes),
        ).isSameAs(firstIndexes)
        assertThat(
            indexesByDocument.canonical(input, layout, collidingPairs, secondIndexes),
        ).isSameAs(secondIndexes)
        assertThat(secondIndexes).isNotSameAs(firstIndexes)
    }

    fun testTokenOnlyIndexesShareByExactObservableContentWithoutRetainingPairGeometry() {
        myFixture.configureByText("TokenOnly.java", "class TokenOnly { value }")
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val firstPairs =
            pairTable(
                intArrayOf(0, 10, 0),
                intArrayOf(2, 8, 0),
            )
        val sameTokensWithDifferentPairs =
            pairTable(
                intArrayOf(0, 8, 0),
                intArrayOf(2, 10, 0),
            )
        assertThat(
            firstPairs.hasSameContent(
                sameTokensWithDifferentPairs,
                NO_CANCELLATION_PROBE,
            ),
        ).isFalse()
        val indexesByDocument = DocumentBracketIndexes()
        val firstIndexes = detachedIndexes(firstPairs)

        indexesByDocument.canonical(input, layout, firstPairs, firstIndexes)

        assertThat(
            indexesByDocument.canonical(
                input,
                layout,
                sameTokensWithDifferentPairs,
                detachedIndexes(sameTokensWithDifferentPairs),
            ),
        ).isSameAs(firstIndexes)
        assertThat(firstIndexes.pairs.isEmpty).isTrue()
    }

    fun testRevisionLayoutAndCoverageAreExactCanonicalBoundaries() {
        myFixture.configureByText("Boundaries.java", "class Boundaries { }")
        val coverage =
            AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = false,
            )
        val input = input(myFixture.editor, coverage)
        val layout = IndexLayout.forCoverage(coverage)
        val pairs = pairTable(openLine = 0, closeLine = 0)
        val indexesByDocument = DocumentBracketIndexes()
        val firstIndexes = indexes(pairs)
        assertThat(
            indexesByDocument.canonical(input, layout, pairs, firstIndexes),
        ).isSameAs(firstIndexes)

        val otherLayoutIndexes = indexes(pairs)
        assertThat(
            indexesByDocument.canonical(
                input,
                layout.copy(tokenStorage = TokenStorage.NONE),
                pairs,
                otherLayoutIndexes,
            ),
        ).isSameAs(otherLayoutIndexes)

        val otherCoverage = coverage.copy(tokens = false)
        val otherCoverageIndexes = indexes(pairs)
        assertThat(
            indexesByDocument.canonical(
                input(myFixture.editor, otherCoverage),
                layout,
                pairs,
                otherCoverageIndexes,
            ),
        ).isSameAs(otherCoverageIndexes)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        val otherRevisionIndexes = indexes(pairs)
        assertThat(
            indexesByDocument.canonical(
                input(myFixture.editor, coverage),
                layout,
                pairs,
                otherRevisionIndexes,
            ),
        ).isSameAs(otherRevisionIndexes)
    }

    fun testTabSizeIsCanonicalBoundaryOnlyForGuideIndexes() {
        myFixture.configureByText("Tabs.java", "class Tabs { }")
        val editor = myFixture.editor
        val pairs = pairTable(openLine = 0, closeLine = 0)
        val guideCoverage =
            AnalysisCoverage(
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
        val guideIndexes =
            indexes(
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

            val otherGuideIndexes =
                indexes(
                    pairs,
                    guidePositions = guidePositions(originalTabSize + 1),
                )
            assertThat(
                guideIndexesByDocument.canonical(
                    input(editor, guideCoverage),
                    guideLayout,
                    pairs,
                    otherGuideIndexes,
                ),
            ).isSameAs(otherGuideIndexes)
            assertThat(
                tokenIndexesByDocument.canonical(
                    input(editor, tokenCoverage),
                    tokenLayout,
                    pairs,
                    indexes(pairs),
                ),
            ).isSameAs(tokenIndexes)
        } finally {
            editor.settings.setTabSize(originalTabSize)
        }
    }

    fun testCanonicalizationIsCancellableAndDoesNotSerializeUnrelatedDocuments() {
        myFixture.configureByText("First.java", "class First { value }")
        val firstEditor = myFixture.editor
        val secondDocument =
            EditorFactory.getInstance().createDocument(
                "class Second { value }",
            )
        val secondEditor =
            EditorFactory.getInstance().createEditor(
                secondDocument,
                project,
                myFixture.file.fileType,
                false,
            )
        val executor = Executors.newFixedThreadPool(3)
        val comparisonEntered = CountDownLatch(1)
        val releaseComparison = CountDownLatch(1)
        try {
            val coverage =
                AnalysisCoverage(
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

            val blockedComparison =
                executor.submit<BracketIndexes> {
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
            assertThat(comparisonEntered.await(1, TimeUnit.SECONDS)).isTrue()

            val secondIndexes = indexes(secondPairs)
            val unrelatedDocument =
                executor.submit<BracketIndexes> {
                    indexesByDocument.canonical(
                        input = secondInput,
                        layout = layout,
                        pairs = secondPairs,
                        candidate = secondIndexes,
                        checkCanceled = {},
                    )
                }
            assertThat(unrelatedDocument.get(1, TimeUnit.SECONDS)).isSameAs(secondIndexes)

            val waitChecks = AtomicInteger()
            val canceledWait =
                executor.submit<BracketIndexes> {
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
            assertThatThrownBy {
                canceledWait.get(1, TimeUnit.SECONDS)
            }.isInstanceOf(ExecutionException::class.java)
                .hasCauseInstanceOf(TestCancellation::class.java)

            releaseComparison.countDown()
            assertThat(blockedComparison.get(1, TimeUnit.SECONDS)).isNotNull()
        } finally {
            releaseComparison.countDown()
            executor.shutdownNow()
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    private fun input(editor: Editor, coverage: AnalysisCoverage): AnalysisInput = AnalysisInput(
        editor = editor,
        fileType = myFixture.file.fileType,
        coverage = coverage,
        disabledLanguageIds = emptySet(),
    )

    private fun indexes(pairs: PairTable, guidePositions: GuidePositionIndex? = null): BracketIndexes = BracketIndexes(
        pairs = pairs,
        tokens = BracketTokenIndex.build(pairs, NO_CANCELLATION),
        activePairs = ActiveBracketPairIndex.build(pairs, NO_CANCELLATION),
        guidePositions = guidePositions,
    )

    private fun detachedIndexes(pairs: PairTable): BracketIndexes = BracketIndexes(
        pairs = PairTable.empty(),
        tokens = BracketTokenIndex.buildDetached(pairs, NO_CANCELLATION),
        activePairs =
        ActiveBracketPairIndex.build(
            PairTable.empty(),
            NO_CANCELLATION,
        ),
        guidePositions = null,
    )

    private fun guidePositions(tabSize: Int): GuidePositionIndex = checkNotNull(
        DocumentGuidePositions(
            document = myFixture.editor.document,
            tabSize = tabSize,
            checkCanceled = NO_CANCELLATION,
        ).index(0 until myFixture.editor.document.lineCount),
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
        assertThat(outcome).isInstanceOf(AnalysisOutcome.Complete::class.java)
        return (outcome as AnalysisOutcome.Complete).snapshot
    }

    private fun <T> inReadAction(action: () -> T): T = ReadAction.compute<T, RuntimeException>(action)

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
