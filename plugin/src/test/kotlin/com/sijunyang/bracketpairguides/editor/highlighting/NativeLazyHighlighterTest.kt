package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.highlighting.BraceHighlightingHandler
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/** Runtime parity reference is the supported241 test SDK; production never calls the internal Handler API. */
internal class NativeLazyHighlighterTest : BasePlatformTestCase() {
    fun testOrdinaryAndUncommittedSourcesReuseTheInstalledHighlighter() = verifyFallbacks()
    fun testOwnedLazyIteratorMatchesPlatformTokenTypesAndAbsoluteEndpoints() = verifyParity()
    fun testLazyPreparationUsesTheCapturedCaretAndTraversalRemainsCancellable() = verifyCapturedCaretAndCancellation()

    private fun configureLazy(): Pair<PsiCodeBlock, Int> {
        myFixture.configureByText(
            "OwnedLazy.java",
            "class OwnedLazy { void run() {\n" + "call(1);\n".repeat(600) + "} }",
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val offset = myFixture.editor.document.text.indexOf("{\n")
        myFixture.editor.caretModel.moveToOffset(offset)
        return checkNotNull(
            PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset), PsiCodeBlock::class.java, false),
        ) to
            offset
    }

    private fun verifyFallbacks() {
        val (_, offset) = configureLazy()
        ReadAction.run<RuntimeException> {
            assertThat(
                NativeLazyHighlighter.prepare(myFixture.editor, myFixture.file, offset) {
                },
            ).isSameAs(myFixture.editor.highlighter)
        }
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        ReadAction.run<RuntimeException> {
            assertThat(PsiDocumentManager.getInstance(project).isCommitted(myFixture.editor.document)).isFalse()
            assertThat(
                NativeLazyHighlighter.prepare(myFixture.editor, myFixture.file, offset) {
                },
            ).isSameAs(myFixture.editor.highlighter)
        }
    }

    private fun verifyParity() {
        val (block, offset) = configureLazy()
        val previous = block.node.getUserData(ILazyParseableElementType.LANGUAGE_KEY)
        block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, JavaLanguage.INSTANCE)
        try {
            ReadAction.run<RuntimeException> {
                val actual = NativeLazyHighlighter.prepare(myFixture.editor, myFixture.file, offset) {}
                val expected = BraceHighlightingHandler.getLazyParsableHighlighterIfAny(
                    project,
                    myFixture.editor,
                    myFixture.file,
                )
                assertThat(actual).isNotSameAs(myFixture.editor.highlighter)
                for (start in listOf(0, offset, offset + 1, myFixture.editor.document.textLength)) {
                    assertThat(tokens(actual, start)).isEqualTo(tokens(expected, start))
                }
            }
        } finally {
            block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, previous)
        }
    }

    private fun verifyCapturedCaretAndCancellation() {
        val (block, offset) = configureLazy()
        val previous = block.node.getUserData(ILazyParseableElementType.LANGUAGE_KEY)
        block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, JavaLanguage.INSTANCE)
        try {
            myFixture.editor.caretModel.moveToOffset(0)
            ReadAction.run<RuntimeException> {
                val prepared = NativeLazyHighlighter.prepare(myFixture.editor, myFixture.file, offset) {}
                assertThat(prepared).isNotSameAs(myFixture.editor.highlighter)
                assertThat(
                    NativeLazyHighlighter.prepare(myFixture.editor, myFixture.file, 0) {
                    },
                ).isSameAs(myFixture.editor.highlighter)
                var traversed = 0
                var probes = 0
                val delegate = prepared.createIterator(offset)
                val observed = object : HighlighterIterator by delegate {
                    override fun advance() {
                        traversed++
                        delegate.advance()
                    }
                }
                val cancellable = CancellableNativeHighlighter.cancellableIterator(observed) {
                    if (++probes == 2) throw TraversalCanceled()
                }
                assertThatThrownBy { while (!cancellable.atEnd()) cancellable.advance() }
                    .isInstanceOf(TraversalCanceled::class.java)
                assertThat(traversed).isEqualTo(256)
                assertThatThrownBy {
                    NativeLazyHighlighter.prepare(myFixture.editor, myFixture.file, offset) {
                        throw TraversalCanceled()
                    }
                }.isInstanceOf(TraversalCanceled::class.java)
            }
        } finally {
            block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, previous)
        }
    }

    private fun tokens(highlighter: EditorHighlighter, offset: Int): List<Triple<Any?, Int, Int>> {
        val iterator = highlighter.createIterator(offset)
        val result = ArrayList<Triple<Any?, Int, Int>>()
        while (!iterator.atEnd()) {
            result += Triple(iterator.tokenType, iterator.start, iterator.end)
            iterator.advance()
        }
        return result
    }

    private class TraversalCanceled : RuntimeException(null, null, false, false)
}
