package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

internal abstract class BracketGuideHighlightingFixture : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    internal fun applyPass(
        pairs: (() -> List<BracketPair>)? = null,
        stickySourceRanges: ((Editor) -> List<TextRange>)? = null,
        visibleRange: ((Editor) -> TextRange)? = null,
    ) {
        val pass =
            if (pairs == null) {
                BracketGuideHighlightingPass(
                    project = project,
                    editor = myFixture.editor,
                    fileType = myFixture.file.fileType,
                    sourceFile = myFixture.file.virtualFile,
                    analyze = service<BracketAnalysis>()::analyze,
                )
            } else {
                testPass(
                    project = project,
                    editor = myFixture.editor,
                    pairs = pairs,
                    visibleRange = visibleRange ?: Editor::calculateVisibleRange,
                    stickySourceRanges = stickySourceRanges ?: { emptyList() },
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
    ): BracketGuideHighlightingPass = BracketGuideHighlightingPass(
        project = project,
        editor = editor,
        analyze = { input, _ ->
            val recognizedPairs =
                if (input.coverage.pairs) {
                    pairs()
                } else {
                    emptyList()
                }
            AnalysisOutcome.Complete(input.bracketSnapshot(recognizedPairs))
        },
        visibleRange = visibleRange,
        stickySourceRanges = stickySourceRanges,
        fileType = fileType,
        sourceFile = FileDocumentManager.getInstance().getFile(editor.document),
    )

    internal fun stampFor(editor: Editor, options: BracketGuidePreferences) = AnalysisInput(
        editor = editor,
        fileType = myFixture.file.fileType,
        coverage = options.analysisCoverage(),
        disabledLanguageIds = options.disabledLanguageIds,
    ).stamp

    internal fun applyPass(pass: BracketGuideHighlightingPass) {
        inReadAction {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
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
