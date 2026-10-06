package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.GuideRepairScheduler
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

internal abstract class BracketGuideHighlightingFixture : BasePlatformTestCase() {
    private val executionLifetime = SupervisorJob()
    private val executionScope = CoroutineScope(executionLifetime + Dispatchers.Default)
    private val executions = java.util.Collections.synchronizedList(mutableListOf<EditorAnalysisExecution>())
    private lateinit var guideRepairExecution: GuideRepairExecution
    private val controlled = java.util.Collections.synchronizedMap(
        IdentityHashMap<BracketGuideHighlightingPass, ControlledResult>(),
    )

    override fun setUp() {
        super.setUp()
        guideRepairExecution = GuideRepairExecution(executionScope)
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    override fun tearDown() {
        try {
            executionLifetime.cancel()
            executions.toList().forEach(Disposer::dispose)
            if (::guideRepairExecution.isInitialized) Disposer.dispose(guideRepairExecution)
            awaitAnalysis()
        } finally {
            super.tearDown()
        }
    }

    /** Runs the actual background pipeline without holding a caller-owned read lock. */
    internal fun analyzeInBackground(
        input: AnalysisInput,
        analyze: suspend (AnalysisInput) -> AnalysisOutcome? = { service<BracketAnalysis>().analyzeInBackground(it) },
    ): AnalysisOutcome = checkNotNull(awaitBackground { analyze(input) }) {
        "Current fixture input became stale before background analysis completed"
    }

    /** Matches daemon construction and collection: a Default worker owns the read action. */
    internal fun <T> inBackgroundReadAction(action: () -> T): T = awaitBackground {
        readAction {
            check(!ApplicationManager.getApplication().isDispatchThread)
            action()
        }
    }

    private fun <T> awaitBackground(action: suspend () -> T): T {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val result = AtomicReference<Result<T>?>()
        val job = executionScope.launch { result.set(runCatching { action() }) }
        try {
            PlatformTestUtil.waitWithEventsDispatching("background fixture work completed", { job.isCompleted }, 10)
            return checkNotNull(result.get()) { "Background fixture work did not run" }.getOrThrow()
        } finally {
            job.cancel()
            PlatformTestUtil.waitWithEventsDispatching("background fixture work released", { job.isCompleted }, 10)
        }
    }

    internal fun guideRepairsFor(editor: Editor): GuideRepairScheduler = { request, isCurrent, publish ->
        guideRepairExecution.request(editor, request, isCurrent, publish)
    }

    internal fun createExecution(
        analyze: suspend (AnalysisInput) -> AnalysisOutcome? = { service<BracketAnalysis>().analyzeInBackground(it) },
        capabilities: (Editor) -> EditorCapabilities = EditorSurfaceClassifier::capabilities,
        activity: (Editor) -> EditorActivity = { EditorActivity.ACTIVE },
        visibleRange: (Editor) -> TextRange = Editor::calculateVisibleRange,
        stickySourceRanges: (Editor) -> List<TextRange> = { emptyList() },
        fileType: (Editor) -> FileType = EditorSurfaceClassifier::fileType,
        sourceFile: (Editor) -> VirtualFile? = EditorSurfaceClassifier::sourceFile,
    ): EditorAnalysisExecution = EditorAnalysisExecution(
        executionScope,
        analyze,
        capabilities,
        activity,
        visibleRange,
        stickySourceRanges,
        fileType,
        sourceFile,
        repairExecution = { guideRepairExecution },
    ).also(executions::add)

    internal fun createPass(
        project: Project,
        editor: Editor,
        fileType: FileType,
        sourceFile: VirtualFile?,
        analyze: ((AnalysisInput, ProgressIndicator) -> AnalysisOutcome)? = null,
        capabilities: (Editor) -> EditorCapabilities = EditorSurfaceClassifier::capabilities,
        activity: (Editor) -> EditorActivity = { EditorActivity.ACTIVE },
        visibleRange: (Editor) -> TextRange = Editor::calculateVisibleRange,
        stickySourceRanges: (Editor) -> List<TextRange> = { emptyList() },
        backgroundAnalyze: suspend (
            AnalysisInput,
        ) -> AnalysisOutcome? = { service<BracketAnalysis>().analyzeInBackground(it) },
    ): BracketGuideHighlightingPass {
        val gate = ControlledResult()
        val previousRoots = executionLifetime.children.toSet()
        val execution = createExecution(
            analyze = { input ->
                val result = if (analyze == null) {
                    backgroundAnalyze(input)
                } else {
                    // Synthetic snapshot fixtures require document geometry while constructing their payload.
                    readAction { analyze(input, EmptyProgressIndicator()) }
                }
                gate.ready.complete(Unit)
                gate.release.await()
                result
            },
            capabilities = capabilities,
            activity = activity,
            visibleRange = visibleRange,
            stickySourceRanges = stickySourceRanges,
            fileType = { fileType },
            sourceFile = { sourceFile },
        )
        gate.root = executionLifetime.children.single { it !in previousRoots }
        if (EditorEffectGuard.allowsEffects() && ApplicationManager.getApplication().isDispatchThread &&
            !editor.isDisposed && capabilities(editor) != EditorCapabilities.NONE
        ) {
            EditorGuideSessions.install(
                editor,
                visibleRange,
                stickySourceRanges,
                BracketGuideSettings.getInstance().options,
                capabilities(editor),
                activity(editor),
                requestRepair = { request, isCurrent, publish ->
                    guideRepairExecution.request(editor, request, isCurrent, publish)
                },
            )
        }
        return inBackgroundReadAction {
            BracketGuideHighlightingPass(project, editor, execution::request)
        }.also { controlled[it] = gate }
    }

    internal fun applyPass(
        pairs: (() -> List<BracketPair>)? = null,
        stickySourceRanges: ((Editor) -> List<TextRange>)? = null,
        visibleRange: ((Editor) -> TextRange)? = null,
    ) {
        val pass = if (pairs == null) {
            createPass(
                project = project,
                editor = myFixture.editor,
                fileType = myFixture.file.fileType,
                sourceFile = myFixture.file.virtualFile,
                activity = { EditorActivity.ACTIVE },
                capabilities = { EditorCapabilities.MAIN },
            )
        } else {
            testPass(
                project,
                myFixture.editor,
                pairs,
                visibleRange ?: Editor::calculateVisibleRange,
                stickySourceRanges ?: { emptyList() },
            )
        }
        applyPass(pass)
    }

    internal fun testPass(
        project: Project,
        editor: Editor,
        pairs: () -> List<BracketPair>,
        visibleRange: (Editor) -> TextRange = Editor::calculateVisibleRange,
        stickySourceRanges: (Editor) -> List<TextRange> = { emptyList() },
        fileType: FileType = myFixture.file.fileType,
    ): BracketGuideHighlightingPass = createPass(
        project = project,
        editor = editor,
        fileType = fileType,
        sourceFile = FileDocumentManager.getInstance().getFile(editor.document),
        activity = { EditorActivity.ACTIVE },
        capabilities = { EditorCapabilities.MAIN },
        analyze = { input, _ ->
            AnalysisOutcome.Complete(input.bracketSnapshot(if (input.coverage.pairs) pairs() else emptyList()))
        },
        visibleRange = visibleRange,
        stickySourceRanges = stickySourceRanges,
    )

    /** Hold only the immutable result; calculation has already released all read access. */
    internal fun collectPass(pass: BracketGuideHighlightingPass) {
        val gate = checkNotNull(controlled[pass])
        gate.release = CompletableDeferred()
        inBackgroundReadAction { pass.doCollectInformation(EmptyProgressIndicator()) }
        PlatformTestUtil.waitWithEventsDispatching(
            "background result is ready",
            { gate.ready.isCompleted || gate.root.children.none { !it.isCompleted } },
            10,
        )
    }

    internal fun publishPass(pass: BracketGuideHighlightingPass) {
        controlled[pass]?.release?.complete(Unit)
        pass.doApplyInformationToEditor()
        awaitPass(pass)
    }

    private fun awaitPass(pass: BracketGuideHighlightingPass) {
        val gate = controlled[pass]
        if (gate == null) {
            awaitAnalysis()
        } else {
            PlatformTestUtil.waitWithEventsDispatching(
                "requested analysis publication completed",
                { gate.root.children.none { !it.isCompleted } },
                10,
            )
        }
    }

    internal fun awaitGuideRepair() = awaitAnalysis()

    internal fun awaitAnalysis() {
        PlatformTestUtil.waitWithEventsDispatching("analysis publication completed", ::analysisIdle, 10)
    }

    private fun analysisIdle(): Boolean = executionLifetime.children.all { root ->
        root.children.none { !it.isCompleted }
    }

    private class ControlledResult {
        lateinit var root: Job
        val ready = CompletableDeferred<Unit>()

        @Volatile var release = CompletableDeferred<Unit>().also { it.complete(Unit) }
    }

    internal fun stampFor(editor: Editor, options: BracketGuidePreferences) = AnalysisInput(
        editor = editor,
        fileType = myFixture.file.fileType,
        coverage = options.analysisCoverage(),
        disabledLanguageIds = options.disabledLanguageIds,
    ).stamp

    internal fun applyPass(pass: BracketGuideHighlightingPass) {
        controlled[pass]?.release?.complete(Unit)
        inBackgroundReadAction { pass.doCollectInformation(EmptyProgressIndicator()) }
        pass.doApplyInformationToEditor()
        awaitPass(pass)
    }

    internal fun ownedHighlighters(): List<RangeHighlighter> = myFixture.editor.observedBracketMarkup().allMarks

    internal fun guideHighlighters(): List<RangeHighlighter> = myFixture.editor.observedBracketMarkup().guideMarks

    internal fun bracketColorHighlighters(): List<RangeHighlighter> =
        myFixture.editor.observedBracketMarkup().tokenMarks

    internal fun activePairHighlighters(): List<RangeHighlighter> =
        myFixture.editor.observedBracketMarkup().activePairMarks

    internal fun activeGuide(): RangeHighlighter? = guideHighlighters().singleOrNull {
        it.customRenderer is BracketGuideDrawing
    }

    internal fun activeGuideState(): BracketGuideDrawing? = activeGuide()?.customRenderer as? BracketGuideDrawing

    internal fun session(): EditorGuideSession = checkNotNull(EditorGuideSessions.get(myFixture.editor))

    internal fun applyOptions(options: BracketGuidePreferences) {
        BracketGuideSettings.getInstance().replace(options)
        session().updateOptions(
            options,
            refreshColors = false,
        )
    }

    internal fun sequentialPairs(pairCount: Int): List<BracketPair> = List(pairCount) { index ->
        val openOffset = index * 2
        BracketPair(openOffset, 1, openOffset + 1, 1, 0, 0, 0)
    }

    internal fun resizeDocument(targetLength: Int) {
        require(targetLength >= 0)
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            when {
                targetLength > document.textLength -> {
                    document.insertString(
                        document.textLength,
                        " ".repeat(targetLength - document.textLength),
                    )
                }

                targetLength < document.textLength -> {
                    document.deleteString(
                        targetLength,
                        document.textLength,
                    )
                }
            }
        }
    }

    internal fun List<Int>.updated(index: Int, value: Int): List<Int> = toMutableList().also { it[index] = value }

    internal fun <T> inReadAction(action: () -> T): T = ReadAction.compute<T, RuntimeException>(action)

    internal class MutableLengthVirtualFile(name: String, @Volatile var reportedLength: Long) : MockVirtualFile(name) {
        override fun getLength(): Long = reportedLength
    }

    internal companion object {
        const val OVERSIZED_FILE_LENGTH: Long = 100L * 1024 * 1024
    }
}
