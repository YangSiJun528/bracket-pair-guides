package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class BackgroundAnalysisLifecycleTest : BracketGuideHighlightingFixture() {
    fun testAppliesActiveGuideBeforeRequestingViewportDecorations() {
        val source = "x { content } y"
        myFixture.configureByText("ActiveFirst.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        BracketGuideSettings.getInstance().replace(
            BracketGuideSettings.getInstance().options.copy(showActivePairBorder = true),
        )
        var activePairWhenViewportWasRequested: BracketPair? = null
        var activeHighlightsWhenViewportWasRequested = 0
        val pass = testPass(
            project = project,
            editor = editor,
            pairs = { listOf(pair) },
            visibleRange = {
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
        val pairs = {
            collections.incrementAndGet()
            listOf(pair)
        }
        EditorGuideSessions.dispose(editor)
        assertNull(EditorGuideSessions.get(editor))
        fun collectInBackground(): BracketGuideHighlightingPass {
            val collection = AppExecutorUtil.getAppExecutorService()
                .submit<BracketGuideHighlightingPass> {
                    inReadAction {
                        testPass(project, editor, pairs).also { pass ->
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
        assertNull(EditorGuideSessions.get(editor))
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

    fun testBackgroundAnalysisUsesStampedLanguageSelectionAcrossAbaChange() {
        val source = "x { content } y"
        myFixture.configureByText("LanguageSelectionSnapshot.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val initialOptions = BracketGuideSettings.getInstance().options
        val disabledDuringCollection = setOf("test.matcher.family")
        val providerEntered = CountDownLatch(1)
        val continueCollection = CountDownLatch(1)
        val capturedDisabledLanguageIds = AtomicReference<Set<String>>()
        val observedGlobalLanguageIds = AtomicReference<Set<String>>()
        val analysis: (AnalysisInput, ProgressIndicator) -> AnalysisOutcome = { input, _ ->
            capturedDisabledLanguageIds.set(input.disabledLanguageIds)
            providerEntered.countDown()
            check(continueCollection.await(10, TimeUnit.SECONDS))
            observedGlobalLanguageIds.set(
                BracketGuideSettings.getInstance().options.disabledLanguageIds,
            )
            val pairs = if (disabledDuringCollection.single() in input.disabledLanguageIds) {
                emptyList()
            } else {
                listOf(pair)
            }
            AnalysisOutcome.Complete(input.bracketSnapshot(pairs))
        }
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = analysis,
        )
        val collection = AppExecutorUtil.getAppExecutorService().submit<Unit> {
            inReadAction {
                pass.doCollectInformation(EmptyProgressIndicator())
            }
        }

        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "background pairs entry",
                { providerEntered.count == 0L },
                10_000,
            )
            BracketGuideSettings.getInstance().replace(
                initialOptions.copy(disabledLanguageIds = disabledDuringCollection),
            )
            continueCollection.countDown()
            PlatformTestUtil.waitWithEventsDispatching(
                "stamped language collection",
                { collection.isDone },
                10_000,
            )
            collection.get()
            BracketGuideSettings.getInstance().replace(initialOptions)

            pass.doApplyInformationToEditor()

            assertEquals(initialOptions.disabledLanguageIds, capturedDisabledLanguageIds.get())
            assertEquals(disabledDuringCollection, observedGlobalLanguageIds.get())
            assertEquals(pair, activeGuideState()?.guide?.pair)
        } finally {
            continueCollection.countDown()
            BracketGuideSettings.getInstance().replace(initialOptions)
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
        EditorGuideSessions.dispose(editor)
        assertNull(EditorGuideSessions.get(editor))
        val staleCollection = AppExecutorUtil.getAppExecutorService()
            .submit<BracketGuideHighlightingPass> {
                inReadAction {
                    testPass(
                        project = project,
                        editor = editor,
                        pairs = { listOf(pair) },
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
        assertNull(EditorGuideSessions.get(editor))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        stalePass.doApplyInformationToEditor()

        assertNull(EditorGuideSessions.get(editor))
    }

    fun testStalePassFromAnotherFileTypeCannotReplaceCurrentDependencies() {
        val source = "class FileTypeChange { Object content; }"
        myFixture.configureByText("FileTypeChange.java", source)
        val editor = myFixture.editor
        val highlighter = editor.highlighter
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        EditorGuideSessions.dispose(editor)
        val stalePass = testPass(
            project = project,
            editor = editor,
            pairs = { listOf(pair) },
            fileType = PlainTextFileType.INSTANCE,
        )
        inReadAction {
            stalePass.doCollectInformation(EmptyProgressIndicator())
        }
        val currentPass = testPass(
            project = project,
            editor = editor,
            pairs = { listOf(pair) },
            fileType = myFixture.file.fileType,
        )
        applyPass(currentPass)

        stalePass.doApplyInformationToEditor()
        assertSame(highlighter, editor.highlighter)
        assertEquals(pair, activeGuideState()?.guide?.pair)
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, " ")
        }

        assertEquals(pair, activeGuideState()?.guide?.pair)
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
        val stalePass = testPass(
            project = project,
            editor = editor,
            pairs = { listOf(pair) },
            visibleRange = {
                staleVisibleRangeCalls++
                TextRange(0, it.document.textLength)
            },
        )
        inReadAction {
            stalePass.doCollectInformation(EmptyProgressIndicator())
        }

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        val currentPass = testPass(
            project = project,
            editor = editor,
            pairs = { listOf(pair) },
            visibleRange = {
                currentVisibleRangeCalls++
                TextRange(0, it.document.textLength)
            },
        )
        applyPass(currentPass)

        stalePass.doApplyInformationToEditor()
        staleVisibleRangeCalls = 0
        currentVisibleRangeCalls = 0

        session().visibleAreaChanged()
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }

        assertEquals(0, staleVisibleRangeCalls)
        assertEquals(1, currentVisibleRangeCalls)
    }
}
