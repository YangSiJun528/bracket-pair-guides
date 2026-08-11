package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtilRt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class AnalysisOutcomePublicationTest : BracketGuideHighlightingFixture() {
    fun testUnavailableAnalysisSuppressesTheSameRequestUntilItsInputChanges() {
        myFixture.configureByText("Dense.java", "class Dense { value }")
        val editor = myFixture.editor
        val fullOptions = BracketGuideSettings.getInstance().options
        var analysisCount = 0
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                analysisCount++
                AnalysisOutcome.Unavailable(input.stamp, AnalysisLimit.PAIR_CAPACITY)
            },
        )

        applyPass(pass)

        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
        assertTrue(
            EditorGuideSessions.canSkipAnalysis(
                editor,
                stampFor(editor, BracketGuideSettings.getInstance().options),
            ),
        )

        applyPass(pass)

        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        assertFalse(
            EditorGuideSessions.canSkipAnalysis(
                editor,
                stampFor(editor, tokenOnlyOptions),
            ),
        )
        applyPass(pass)

        assertEquals(2, analysisCount)
        assertTrue(
            EditorGuideSessions.canSkipAnalysis(
                editor,
                stampFor(editor, tokenOnlyOptions),
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, " ")
        }
        applyPass(pass)

        assertEquals(3, analysisCount)
    }

    fun testUnsavedLargeDocumentClearsPresentationWithoutRunningAnalysis() {
        val source = "class Large { value }"
        myFixture.configureByText("Large.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        applyPass(pairs = { listOf(pair) })
        assertTrue(editor.observedBracketMarkup().allMarks.isNotEmpty())
        @Suppress("DEPRECATION")
        val codeInsightBoundary = FileUtilRt.getUserFileSizeLimit()
        resizeDocument(codeInsightBoundary + 1)

        var analysisCount = 0
        val sourceFile = MutableLengthVirtualFile("Large.java", reportedLength = 0L)
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = sourceFile,
            analyze = { input, _ ->
                analysisCount++
                AnalysisOutcome.Complete(
                    input.bracketSnapshot(listOf(pair)),
                )
            },
        )

        applyPass(pass)

        assertEquals(0, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
    }

    fun testCurrentDocumentPolicyDominatesStalePassAndShrinkingCanRecover() {
        val source = "class Mutable { value }"
        myFixture.configureByText("Mutable.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val sourceFile = MutableLengthVirtualFile("Mutable.java", 0L)
        var analysisCount = 0
        fun pass(): BracketGuideHighlightingPass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = sourceFile,
            analyze = { input, _ ->
                analysisCount++
                AnalysisOutcome.Complete(
                    input.bracketSnapshot(listOf(pair)),
                )
            },
        )

        applyPass(pass())
        val completedMarks = editor.observedBracketMarkup().allMarks.toSet()
        assertEquals(1, analysisCount)
        assertTrue(completedMarks.isNotEmpty())

        val staleSmallPass = pass()
        inReadAction {
            staleSmallPass.doCollectInformation(EmptyProgressIndicator())
        }
        val tokenOnlyOptions = BracketGuideSettings.getInstance().options.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        assertTrue(editor.observedBracketMarkup().allMarks.isNotEmpty())
        @Suppress("DEPRECATION")
        val codeInsightBoundary = FileUtilRt.getUserFileSizeLimit()
        resizeDocument(codeInsightBoundary + 1)

        staleSmallPass.doApplyInformationToEditor()

        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())

        applyPass(pass())
        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())

        val staleLargeRefusal = pass()
        inReadAction {
            staleLargeRefusal.doCollectInformation(EmptyProgressIndicator())
        }
        resizeDocument(source.length)
        staleLargeRefusal.doApplyInformationToEditor()

        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())
        applyPass(pass())

        assertEquals(2, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isNotEmpty())
    }

    fun testExactPlatformBoundaryIsAllowedAndNullSourceUsesEngineCaps() {
        val source = "class Boundary { value }"
        myFixture.configureByText("Boundary.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        @Suppress("DEPRECATION")
        val exactBoundary = FileUtilRt.getUserFileSizeLimit().toLong()
        val sourceFile = MutableLengthVirtualFile(
            "Boundary.java",
            OVERSIZED_FILE_LENGTH,
        )
        var analysisCount = 0
        val analysis: (AnalysisInput, com.intellij.openapi.progress.ProgressIndicator) ->
            AnalysisOutcome = { input, _ ->
                analysisCount++
                AnalysisOutcome.Complete(
                    input.bracketSnapshot(listOf(pair)),
                )
            }

        resizeDocument(exactBoundary.toInt())
        applyPass(
            BracketGuideHighlightingPass(
                project,
                editor,
                myFixture.file.fileType,
                sourceFile,
                analysis,
            ),
        )
        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isNotEmpty())

        sourceFile.reportedLength = 0L
        resizeDocument(exactBoundary.toInt() + 1)
        applyPass(
            BracketGuideHighlightingPass(
                project,
                editor,
                myFixture.file.fileType,
                sourceFile,
                analysis,
            ),
        )
        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isEmpty())

        applyPass(
            BracketGuideHighlightingPass(
                project = project,
                editor = editor,
                fileType = myFixture.file.fileType,
                sourceFile = null,
                analyze = analysis,
            ),
        )
        assertEquals(2, analysisCount)
        assertTrue(editor.observedBracketMarkup().allMarks.isNotEmpty())
    }

    fun testLateLimitedCannotDowngradeCompletedGuideForTheSameStamp() {
        val source = "class Stable {\n  value\n}"
        myFixture.configureByText("StableGuide.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )
        val lateLimited = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                val completedInput = AnalysisInput(
                    editor = input.editor,
                    fileType = input.fileType,
                    coverage = input.coverage.copy(guidePosition = false),
                    disabledLanguageIds = input.disabledLanguageIds,
                )
                AnalysisOutcome.Limited(
                    stamp = input.stamp,
                    snapshot = completedInput.bracketSnapshot(listOf(pair)),
                    limit = AnalysisLimit.GUIDE_CAPACITY,
                )
            },
        )
        inReadAction {
            lateLimited.doCollectInformation(EmptyProgressIndicator())
        }
        val complete = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                AnalysisOutcome.Complete(
                    input.bracketSnapshot(listOf(pair)),
                )
            },
        )
        applyPass(complete)
        val completedGuideMarks = editor.observedBracketMarkup().guideMarks.toSet()
        assertTrue(completedGuideMarks.isNotEmpty())

        lateLimited.doApplyInformationToEditor()

        assertEquals(
            completedGuideMarks,
            editor.observedBracketMarkup().guideMarks.toSet(),
        )
        assertTrue(
            EditorGuideSessions.canSkipAnalysis(
                editor,
                stampFor(editor, BracketGuideSettings.getInstance().options),
            ),
        )
    }

    fun testGuideCapacityKeepsTokensAndActivePairWithoutApproximateGuide() {
        val source = "class Limited {\n  value\n}"
        myFixture.configureByText("Limited.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val options = BracketGuideSettings.getInstance().options.copy(
            showActivePairBorder = true,
        )
        BracketGuideSettings.getInstance().replace(options)
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )
        var analysisCount = 0
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                analysisCount++
                val completedInput = AnalysisInput(
                    editor = input.editor,
                    fileType = input.fileType,
                    coverage = input.coverage.copy(guidePosition = false),
                    disabledLanguageIds = input.disabledLanguageIds,
                )
                AnalysisOutcome.Limited(
                    stamp = input.stamp,
                    snapshot = completedInput.bracketSnapshot(listOf(pair)),
                    limit = AnalysisLimit.GUIDE_CAPACITY,
                )
            },
        )

        applyPass(pass)

        val markup = editor.observedBracketMarkup()
        assertEquals(2, markup.tokenMarks.size)
        assertEquals(2, markup.activePairMarks.size)
        assertTrue(markup.guideMarks.isEmpty())
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, stampFor(editor, options)))

        applyPass(pass)

        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().guideMarks.isEmpty())
    }

    fun testGuideCapacityRefusalSurvivesGuideSettingsRoundTrips() {
        val source = "class Limited {\n  value\n}"
        myFixture.configureByText("LimitedSettings.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val fullOptions = BracketGuideSettings.getInstance().options.copy(
            showActivePairBorder = true,
        )
        BracketGuideSettings.getInstance().replace(fullOptions)
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )
        var analysisCount = 0
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                analysisCount++
                if (input.coverage.guidePosition) {
                    val completedInput = AnalysisInput(
                        editor = input.editor,
                        fileType = input.fileType,
                        coverage = input.coverage.copy(guidePosition = false),
                        disabledLanguageIds = input.disabledLanguageIds,
                    )
                    AnalysisOutcome.Limited(
                        stamp = input.stamp,
                        snapshot = completedInput.bracketSnapshot(listOf(pair)),
                        limit = AnalysisLimit.GUIDE_CAPACITY,
                    )
                } else {
                    AnalysisOutcome.Complete(
                        input.bracketSnapshot(listOf(pair)),
                    )
                }
            },
        )

        applyPass(pass)
        assertEquals(1, analysisCount)
        assertTrue(editor.observedBracketMarkup().guideMarks.isEmpty())

        val activeWithoutGuide = fullOptions.copy(showActiveGuide = false)
        applyOptions(activeWithoutGuide)
        applyOptions(fullOptions)

        assertTrue(editor.observedBracketMarkup().guideMarks.isEmpty())
        applyPass(pass)
        assertEquals(
            "Exact lower facets and their guide refusal should satisfy the restored request",
            1,
            analysisCount,
        )

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        applyPass(pass)
        assertEquals(2, analysisCount)
        applyOptions(fullOptions)

        assertTrue(editor.observedBracketMarkup().guideMarks.isEmpty())
        applyPass(pass)

        assertEquals(
            "A guide refusal must not claim active-pair facets released by compaction",
            3,
            analysisCount,
        )
        val restoredMarkup = editor.observedBracketMarkup()
        assertEquals(2, restoredMarkup.activePairMarks.size)
        assertTrue(restoredMarkup.guideMarks.isEmpty())
    }

    fun testLateRicherUnavailableCannotClearACompletedLowerCoverage() {
        val source = "class Dense { value }"
        myFixture.configureByText("DenseCoverage.java", source)
        val editor = myFixture.editor
        val fullOptions = BracketGuideSettings.getInstance().options
        val fullStamp = stampFor(editor, fullOptions)
        val lateUnavailable = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                AnalysisOutcome.Unavailable(input.stamp, AnalysisLimit.PAIR_CAPACITY)
            },
        )
        inReadAction {
            lateUnavailable.doCollectInformation(EmptyProgressIndicator())
        }

        val tokenOnlyOptions = fullOptions.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        applyOptions(tokenOnlyOptions)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        applyPass(pairs = { listOf(pair) })
        val tokenOnlyStamp = stampFor(editor, tokenOnlyOptions)
        val completedMarks = editor.observedBracketMarkup().allMarks.toSet()

        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        assertTrue(completedMarks.isNotEmpty())

        lateUnavailable.doApplyInformationToEditor()
        session().accept(AnalysisOutcome.Unavailable(fullStamp, AnalysisLimit.PAIR_CAPACITY))

        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, tokenOnlyStamp))
        assertEquals(completedMarks, editor.observedBracketMarkup().allMarks.toSet())
    }

    fun testLateUnavailableCannotDowngradeACompletedEquivalentAnalysis() {
        val source = "class Stable { value }"
        myFixture.configureByText("StableOutcome.java", source)
        val editor = myFixture.editor
        val options = BracketGuideSettings.getInstance().options
        val lateUnavailable = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                AnalysisOutcome.Unavailable(input.stamp, AnalysisLimit.PAIR_CAPACITY)
            },
        )
        inReadAction {
            lateUnavailable.doCollectInformation(EmptyProgressIndicator())
        }

        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        applyPass(pairs = { listOf(pair) })
        val completedMarks = editor.observedBracketMarkup().allMarks.toSet()

        lateUnavailable.doApplyInformationToEditor()

        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, stampFor(editor, options)))
        assertEquals(completedMarks, editor.observedBracketMarkup().allMarks.toSet())
    }
}
