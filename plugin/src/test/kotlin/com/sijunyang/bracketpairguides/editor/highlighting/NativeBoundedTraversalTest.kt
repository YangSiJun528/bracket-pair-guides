package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.BracePair
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class NativeBoundedTraversalTest : BasePlatformTestCase() {
    fun testIrregularChunksPreserveNativeResultAndTerminalCursor() = verifyTraversal()
    fun testIrregularContextChunksPreserveEveryBoundaryAndCursorMode() = verifyContexts()

    fun testSuspensionPreservesMatcherCallbackTraceWithoutReplay() = verifyTrace()

    fun testCanceledContinuationCannotResumePartiallyMutatedState() = verifyFailStop()

    private fun verifyFailStop() {
        val text = "class C { void f() { call(1); } }"
        myFixture.configureByText("Cancel.java", text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        ReadAction.run<RuntimeException> {
            val iterator = NativeCursor.create(myFixture.editor.highlighter, text.indexOf('{')) { }
            val scan = NativeBraceMatching.matching(text, myFixture.file.fileType, iterator, true) { }
            val failure = IllegalArgumentException("fixture cancellation")
            assertThatThrownBy {
                scan.advance(text, iterator, NativeBraceMatching.WorkBudget(1024, 0) { throw failure }, { })
            }.isSameAs(failure)
            assertThatThrownBy {
                scan.advance(text, iterator, NativeBraceMatching.WorkBudget(1024, 0) { }, { })
            }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    private fun verifyTrace() {
        val text = "class Order { void run() { call(([1)]); } }"
        myFixture.configureByText("Order.java", text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val original = checkNotNull(LanguageBraceMatching.INSTANCE.forLanguage(JavaLanguage.INSTANCE))
        val calls = mutableListOf<String>()
        val recording = object : PairedBraceMatcher by original {
            override fun getPairs(): Array<BracePair> {
                calls += "pairs"
                return original.pairs
            }
        }
        LanguageBraceMatching.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, recording)
        try {
            ReadAction.run<RuntimeException> {
                val source = myFixture.editor.highlighter
                val offset = text.indexOf('(')
                BraceMatchingUtil.matchBrace(text, myFixture.file.fileType, source.createIterator(offset), true)
                val expected = calls.toList()
                for (size in listOf(1, 3, 7, 1024)) {
                    calls.clear()
                    val initial = NativeCursor.create(source, offset) { }
                    val scan = NativeBraceMatching.matching(text, myFixture.file.fileType, initial, true) { }
                    var cursor = initial.bookmark()
                    var step: NativeBraceMatching.Step
                    do {
                        val iterator = cursor.restore(source) { }
                        step = scan.advance(text, iterator, NativeBraceMatching.WorkBudget(size, 0) { }, { })
                        cursor = iterator.bookmark()
                    } while (step == NativeBraceMatching.Step.MORE)
                    assertThat(calls).isEqualTo(expected)
                }
            }
        } finally {
            LanguageBraceMatching.INSTANCE.removeExplicitExtension(JavaLanguage.INSTANCE, recording)
        }
    }

    private fun verifyTraversal() {
        for ((name, text) in corpus()) {
            myFixture.configureByText(name, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            ReadAction.run<RuntimeException> {
                val source = myFixture.editor.highlighter
                for (offset in text.indices) {
                    for (forward in listOf(false, true)) {
                        val expected = source.createIterator(offset)
                        val matched = BraceMatchingUtil.matchBrace(text, myFixture.file.fileType, expected, forward)
                        val actual = NativeCursor.create(source, offset) { }
                        val session = NativeBraceMatching.matching(text, myFixture.file.fileType, actual, forward) { }
                        val initial = actual.bookmark()
                        var cursor = initial
                        var step: NativeBraceMatching.Step
                        var index = 0
                        do {
                            val iterator = cursor.restore(source) { }
                            step =
                                session.advance(
                                    text,
                                    iterator,
                                    NativeBraceMatching.WorkBudget(
                                        listOf(1, 7, 3, 32)[
                                            index++ %
                                                4,
                                        ],
                                        0,
                                    ) { },
                                    { },
                                )
                            cursor = iterator.bookmark()
                        } while (step == NativeBraceMatching.Step.MORE)
                        val terminal = cursor.restore(source) { }
                        assertThat(step == NativeBraceMatching.Step.MATCHED).isEqualTo(matched)
                        assertThat(terminal.atEnd()).isEqualTo(expected.atEnd())
                        if (!expected.atEnd()) {
                            assertThat(terminal.start).isEqualTo(expected.start)
                            assertThat(terminal.end).isEqualTo(expected.end)
                        }
                    }
                    val expected = source.createIterator(offset)
                    val found = BraceMatchingUtil.findStructuralLeftBrace(myFixture.file.fileType, expected, text)
                    val initial = NativeCursor.create(source, offset) { }
                    val scan = NativeBraceMatching.structuralLeft(myFixture.file.fileType, initial)
                    var cursor = initial.bookmark()
                    var step: NativeBraceMatching.Step
                    do {
                        val iterator = cursor.restore(source) { }
                        step = scan.advance(text, iterator, NativeBraceMatching.WorkBudget(3, 0) { }, { })
                        cursor = iterator.bookmark()
                    } while (step == NativeBraceMatching.Step.MORE)
                    val terminal = cursor.restore(source) { }
                    assertThat(step == NativeBraceMatching.Step.MATCHED).isEqualTo(found)
                    assertThat(terminal.atEnd()).isEqualTo(expected.atEnd())
                    if (!expected.atEnd()) assertThat(terminal.start).isEqualTo(expected.start)
                }
            }
        }
    }

    private fun verifyContexts() {
        for ((name, text) in corpus()) {
            myFixture.configureByText(name, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val editor = myFixture.editor
            val original = editor.settings.isBlockCursor
            try {
                for (block in listOf(false, true)) {
                    for (offset in 0..text.length) {
                        editor.settings.isBlockCursor = block
                        editor.caretModel.moveToOffset(offset)
                        ReadAction.run<RuntimeException> {
                            val expected = BraceMatchingUtil.computeHighlightingAndNavigationContext(
                                editor,
                                myFixture.file,
                            )?.let {
                                NativeBraceContext.Context(it.currentBraceOffset(), it.navigationOffset())
                            }
                            val source = NativeLazyHighlighter.prepare(editor, myFixture.file, offset) { }
                            val session = NativeBraceContext.begin(source, myFixture.file, text, offset, block) { }
                            var index = 0
                            while (!session.advance(
                                    text,
                                    NativeBraceMatching.WorkBudget(listOf(1, 3, 17)[index++ % 3], 0) {
                                    },
                                    { },
                                )
                            ) { }
                            assertThat(session.result()).isEqualTo(expected)
                        }
                    }
                }
            } finally {
                editor.settings.isBlockCursor = original
            }
        }
    }

    private fun corpus() = listOf(
        "Bounded.java" to "class B { void f() { call((1)); { call(2); } } }",
        "Recovery.java" to "class B { void f() { call([1)); }",
        "Bounded.xml" to "<root><child attr=\"x\">text</child><empty/></root>",
        "Recovery.xml" to "<root><child></root>",
        "Case.html" to "<BODY><div>text</DIV></body>",
    )
}
