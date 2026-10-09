package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat

internal class EditorSurfacePolicyTest : BracketGuideHighlightingFixture() {
    private val source = "class Example { int value; }"

    fun testPreviewDiffConsoleAndUntypedEditorsUseOnlyTokenDecorations() {
        configure()
        for (kind in listOf(EditorKind.PREVIEW, EditorKind.DIFF, EditorKind.CONSOLE, EditorKind.UNTYPED)) {
            withEditor(kind) { editor ->
                var coverage: AnalysisCoverage? = null
                val pass = pass(editor, onAnalysis = { coverage = it })
                applyPass(pass)
                assertThat(coverage).describedAs(kind.name).isEqualTo(AnalysisCoverage(true, false, false))
                assertThat(editor.observedBracketMarkup().tokenMarks).hasSize(2)
                assertThat(editor.observedBracketMarkup().guideMarks).isEmpty()
                assertThat(editor.observedBracketMarkup().activePairMarks).isEmpty()
                assertThat(editor.observedBracketMarkup().tokenMarks.all { it.customRenderer == null }).isTrue()
            }
        }
    }

    fun testReadonlyMainEditorCanHighlightAndDrawAtItsActiveCaret() {
        configure()
        withEditor(EditorKind.MAIN_EDITOR) { editor ->
            assertThat(editor.isViewer).isTrue()
            applyPass(pass(editor))
            assertThat(editor.observedBracketMarkup().guideMarks).hasSize(1)
            assertThat(editor.observedBracketMarkup().activePairMarks).hasSize(2)
        }
    }

    fun testSharedDocumentEditorsKeepIndependentPresentationAndAnalysisPolicies() {
        configure()
        withEditor(EditorKind.MAIN_EDITOR) { main ->
            withEditor(EditorKind.PREVIEW) { preview ->
                applyPass(pass(main))
                applyPass(pass(preview))
                assertThat(main.document).isSameAs(preview.document)
                assertThat(main.observedBracketMarkup().guideMarks).hasSize(1)
                assertThat(preview.observedBracketMarkup().guideMarks).isEmpty()
                assertThat(main.observedBracketMarkup().tokenMarks).hasSize(2)
                assertThat(preview.observedBracketMarkup().tokenMarks).hasSize(2)
                assertThat(BracketGuideSettings.getInstance().options.showActiveGuide).isTrue()
            }
        }
    }

    fun testLosingAndRegainingFocusReusesAnalysisAndTokenHighlighters() {
        configure()
        withEditor(EditorKind.MAIN_EDITOR) { editor ->
            var activity = EditorActivity.ACTIVE
            var analyses = 0
            applyPass(pass(editor, { activity }) { analyses++ })
            val originalTokens = editor.observedBracketMarkup().tokenMarks
            activity = EditorActivity(true, false)
            EditorGuideSessions.get(editor)!!.updateSurface(EditorSurfaceClassifier.capabilities(editor), activity)
            assertThat(editor.observedBracketMarkup().guideMarks).isEmpty()
            assertThat(editor.observedBracketMarkup().activePairMarks).isEmpty()
            assertThat(editor.observedBracketMarkup().tokenMarks).containsExactlyElementsOf(originalTokens)
            activity = EditorActivity.ACTIVE
            EditorGuideSessions.get(editor)!!.updateSurface(EditorSurfaceClassifier.capabilities(editor), activity)
            applyPass(pass(editor, { activity }) { analyses++ })
            assertThat(analyses).isEqualTo(1)
            assertThat(editor.observedBracketMarkup().guideMarks).hasSize(1)
            assertThat(editor.observedBracketMarkup().tokenMarks).containsExactlyElementsOf(originalTokens)
        }
    }

    fun testLateAnalysisUsesCurrentActivityInsteadOfRevivingAnInactiveGuide() {
        configure()
        withEditor(EditorKind.MAIN_EDITOR) { editor ->
            var activity = EditorActivity.ACTIVE
            val pass = pass(editor, { activity })
            collectPass(pass)
            activity = EditorActivity(true, false)
            EditorGuideSessions.get(editor)!!.updateSurface(EditorSurfaceClassifier.capabilities(editor), activity)
            publishPass(pass)
            assertThat(editor.observedBracketMarkup().guideMarks).isEmpty()
            assertThat(editor.observedBracketMarkup().activePairMarks).isEmpty()
            assertThat(editor.observedBracketMarkup().tokenMarks).hasSize(2)
        }
    }

    fun testHiddenEditorRemovesMarkupAndRestoresValidAnalysisWhenShown() {
        configure()
        withEditor(EditorKind.MAIN_EDITOR) { editor ->
            applyPass(pass(editor))
            val session = EditorGuideSessions.get(editor)!!
            session.updateSurface(EditorSurfaceClassifier.capabilities(editor), EditorActivity.INACTIVE)
            assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
            session.updateSurface(EditorSurfaceClassifier.capabilities(editor), EditorActivity.ACTIVE)
            assertThat(editor.observedBracketMarkup().tokenMarks).hasSize(2)
            assertThat(editor.observedBracketMarkup().guideMarks).hasSize(1)
        }
    }

    fun testDocumentEditInInactiveEditorDoesNotRecreateGuide() {
        configure()
        withEditor(EditorKind.MAIN_EDITOR) { editor ->
            applyPass(pass(editor))
            val session = EditorGuideSessions.get(editor)!!
            session.updateSurface(EditorSurfaceClassifier.capabilities(editor), EditorActivity(true, false))
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(source.indexOf("value"), "longer")
            }
            assertThat(editor.observedBracketMarkup().guideMarks).isEmpty()
            assertThat(editor.observedBracketMarkup().tokenMarks.map { it.startOffset }.sorted())
                .containsExactly(source.indexOf('{'), source.indexOf('}') + 6)
        }
    }

    private fun configure() {
        myFixture.configureByText("Example.java", source)
        BracketGuideSettings.getInstance().replace(
            BracketGuideSettings.getInstance().options.copy(showActivePairBorder = true),
        )
    }

    private fun pass(
        editor: Editor,
        activity: (Editor) -> EditorActivity = { EditorActivity.ACTIVE },
        onAnalysis: (AnalysisCoverage) -> Unit = {},
    ) = createPass(
        project = project,
        editor = editor,
        fileType = myFixture.file.fileType,
        sourceFile = myFixture.file.virtualFile,
        activity = activity,
        visibleRange = { TextRange(0, it.document.textLength) },
        stickySourceRanges = { emptyList() },
        backgroundAnalyze = { input ->
            onAnalysis(input.coverage)
            service<BracketAnalysis>().analyzeInBackground(input)
        },
    )

    private fun withEditor(kind: EditorKind, action: (Editor) -> Unit) {
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(myFixture.editor.document, project, kind)
        try {
            (editor as EditorEx).setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType),
            )
            editor.caretModel.moveToOffset(source.indexOf("value"))
            action(editor)
        } finally {
            factory.releaseEditor(editor)
        }
    }
}
