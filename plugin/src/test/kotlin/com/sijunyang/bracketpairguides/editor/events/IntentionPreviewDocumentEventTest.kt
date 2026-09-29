package com.sijunyang.bracketpairguides.editor.events

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.highlighting.BracketGuideHighlightingFixture
import org.assertj.core.api.Assertions.assertThat

internal class IntentionPreviewDocumentEventTest : BracketGuideHighlightingFixture() {
    fun testDocumentEditInPreviewLeavesGuidesUnchanged() {
        checkPreviewAndSubsequentEdit(editPsi = false)
    }

    fun testPsiEditInPreviewLeavesGuidesUnchanged() {
        checkPreviewAndSubsequentEdit(editPsi = true)
    }

    private fun checkPreviewAndSubsequentEdit(editPsi: Boolean) {
        val source = "class Sample { int value; }"
        myFixture.configureByText("Sample.java", source)
        myFixture.editor.caretModel.moveToOffset(source.indexOf("value"))
        applyPass(
            pairs = {
                listOf(BracketPair(source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0))
            },
        )
        val originalGuides = guideHighlighters()
        val originalTokens = bracketColorHighlighters()
        assertThat(originalGuides).hasSize(1)
        assertThat(originalTokens).hasSize(2)

        val preview = myFixture.getIntentionPreviewText(RenameFieldIntention(editPsi))

        assertThat(preview).isEqualTo(source.replace("value", "updated"))
        assertThat(myFixture.editor.document.text).isEqualTo(source)
        assertThat(guideHighlighters()).containsExactlyElementsOf(originalGuides)
        assertThat(bracketColorHighlighters()).containsExactlyElementsOf(originalTokens)
        assertThat(originalTokens.map { it.startOffset }.sorted())
            .containsExactly(source.indexOf('{'), source.indexOf('}'))

        WriteCommandAction.runWriteCommandAction(project) {
            val offset = source.indexOf("value")
            myFixture.editor.document.replaceString(offset, offset + "value".length, "updated")
        }

        // No daemon pass or queued event may be needed to repair the real editor.
        assertThat(bracketColorHighlighters().map { it.startOffset }.sorted())
            .containsExactly(source.indexOf('{'), source.indexOf('}') + 2)
        assertThat(guideHighlighters()).containsExactlyElementsOf(originalGuides)
    }

    private class RenameFieldIntention(private val editPsi: Boolean) : IntentionAction {
        override fun getText(): String = "Rename field in preview"

        override fun getFamilyName(): String = text

        override fun startInWriteAction(): Boolean = true

        override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = true

        override fun invoke(project: Project, editor: Editor, file: PsiFile) {
            assertThat(IntentionPreviewUtils.isIntentionPreviewActive()).isTrue()
            assertThat(ApplicationManager.getApplication().isDispatchThread).isFalse()
            assertThat(file.isPhysical).isFalse()
            val document = checkNotNull(file.viewProvider.document)
            assertThat(EditorFactory.getInstance().getEditors(document)).isEmpty()
            if (editPsi) {
                val field = (file as PsiJavaFile).classes.single().fields.single()
                field.nameIdentifier.replace(JavaPsiFacade.getElementFactory(project).createIdentifier("updated"))
            } else {
                val offset = document.text.indexOf("value")
                document.replaceString(offset, offset + "value".length, "updated")
            }
        }
    }
}
