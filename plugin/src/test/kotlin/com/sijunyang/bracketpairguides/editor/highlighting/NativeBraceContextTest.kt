package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.highlighting.BraceHighlightingHandler
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.BracePair
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NativeBraceContextTest : BasePlatformTestCase() {
    fun testJavaAndXmlContextParityAtEveryCaretBoundaryAndCursorMode() {
        for ((name, text) in listOf(
            "Context.java" to "class Context { void run() { call((1)); } }",
            "Unmatched.java" to "class Context { void run() { call((1); }",
            "Context.xml" to "<root><child attr=\"x\">text</child><empty/></root>",
            "Unmatched.xml" to "<root><child></root>",
        )) {
            myFixture.configureByText(name, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val editor = myFixture.editor
            val previous = editor.settings.isBlockCursor
            try {
                for (block in listOf(false, true)) {
                    editor.settings.isBlockCursor = block
                    for (offset in 0..text.length) {
                        editor.caretModel.moveToOffset(offset)
                        ReadAction.run<RuntimeException> { assertContextParity(offset, block) }
                    }
                }
            } finally {
                editor.settings.isBlockCursor = previous
            }
        }
    }

    fun testLazyParsableSourceUsesThePlatformsPreparedHighlighter() {
        myFixture.configureByText("Lazy.java", "class Lazy { void run() { call((1)); } }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val offset = myFixture.editor.document.text.indexOf("{ call")
        myFixture.editor.caretModel.moveToOffset(offset)
        val block =
            checkNotNull(
                PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset), PsiCodeBlock::class.java, false),
            )
        assertThat(block.node.elementType).isInstanceOf(ILazyParseableElementType::class.java)
        val original = block.node.getUserData(ILazyParseableElementType.LANGUAGE_KEY)
        block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, JavaLanguage.INSTANCE)
        try {
            ReadAction.run<RuntimeException> {
                assertThat(
                    BraceHighlightingHandler.getLazyParsableHighlighterIfAny(project, myFixture.editor, myFixture.file),
                )
                    .isNotSameAs(myFixture.editor.highlighter)
                assertContextParity(offset, false)
                assertContextParity(offset + 1, false)
            }
        } finally {
            block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, original)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testActualContextTraversalRunsInBackgroundReadAction() {
        myFixture.configureByText("Background.java", "class Background { void run() { call(1); } }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val offset = myFixture.editor.document.text.indexOf('{')
        myFixture.editor.caretModel.moveToOffset(offset)
        val expected = ReadAction.compute<NativeBraceContext.Context?, RuntimeException> {
            BraceMatchingUtil.computeHighlightingAndNavigationContext(myFixture.editor, myFixture.file)?.let {
                NativeBraceContext.Context(it.currentBraceOffset(), it.navigationOffset())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = scope.async {
                readAction {
                    assertThat(ApplicationManager.getApplication().isDispatchThread).isFalse()
                    NativeBraceContext.compute(myFixture.editor, myFixture.file, offset, false) {
                        assertThat(ApplicationManager.getApplication().isReadAccessAllowed).isTrue()
                    }
                }
            }
            PlatformTestUtil.waitWithEventsDispatching("Native context worker did not finish", {
                worker.isCompleted
            }, 10)
            assertThat(worker.getCompleted()).isEqualTo(expected)
        } finally {
            scope.cancel()
        }
    }

    fun testCancellationInterruptsRealForwardAndStructuralBackwardTraversal() {
        myFixture.configureByText("Long.java", "class Long { void run() {\n" + "call(1);\n".repeat(2000) + "} }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val editor = myFixture.editor
        ReadAction.run<RuntimeException> {
            val chars = editor.document.charsSequence
            for (forward in listOf(true, false)) {
                val offset = if (forward) chars.indexOf('{') else chars.lastIndexOf("call")
                var traversed = 0
                var probes = 0
                val delegate = editor.highlighter.createIterator(offset)
                val observed = object : HighlighterIterator by delegate {
                    override fun advance() {
                        traversed++
                        delegate.advance()
                    }
                    override fun retreat() {
                        traversed++
                        delegate.retreat()
                    }
                }
                val iterator = CancellableNativeHighlighter.cancellableIterator(observed) {
                    if (++probes == 2) throw TraversalCanceled()
                }
                assertThatThrownBy {
                    if (forward) {
                        BraceMatchingUtil.matchBrace(chars, myFixture.file.fileType, iterator, true)
                    } else {
                        BraceMatchingUtil.findStructuralLeftBrace(myFixture.file.fileType, iterator, chars)
                    }
                }.isInstanceOf(TraversalCanceled::class.java)
                assertThat(probes).isEqualTo(2)
                assertThat(traversed).isEqualTo(256)
            }
        }
    }

    fun testCancellationAlsoWrapsTheLazyPreparedSourceTraversal() {
        myFixture.configureByText(
            "LazyLong.java",
            "class LazyLong { void run() {\n" + "call(1);\n".repeat(2000) + "} }",
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val offset = myFixture.editor.document.text.indexOf("{\n")
        myFixture.editor.caretModel.moveToOffset(offset)
        val block =
            checkNotNull(
                PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset), PsiCodeBlock::class.java, false),
            )
        val original = block.node.getUserData(ILazyParseableElementType.LANGUAGE_KEY)
        block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, JavaLanguage.INSTANCE)
        try {
            ReadAction.run<RuntimeException> {
                var probes = 0
                assertThatThrownBy {
                    NativeBraceContext.compute(myFixture.editor, myFixture.file, offset, false) {
                        if (++probes == 11) throw TraversalCanceled()
                    }
                }.isInstanceOf(TraversalCanceled::class.java)
                assertThat(probes).isEqualTo(11)
            }
        } finally {
            block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, original)
        }
    }

    fun testOwnedMatchingPreservesPlatformResultsAndTerminalIteratorOnRecovery() =
        verifyOwnedMatchingPreservesPlatformResultsAndTerminalIteratorOnRecovery()

    private fun verifyOwnedMatchingPreservesPlatformResultsAndTerminalIteratorOnRecovery() {
        for ((name, text) in listOf(
            "Pairs.java" to "class Pairs { void run() { call(([1])); } }",
            "Recovery.java" to "class Recovery { void run() { call(([1)]); } }",
            "Incomplete.java" to "class Incomplete { void run() { call(([1);",
            "Pairs.xml" to "<root><a><b/></a><x attr=\"v\"/></root>",
            "Mismatch.xml" to "<root><a><b></a></b></root>",
            "Case.xml" to "<Root><Child></child></Root>",
            "Recovery.html" to "<HTML><body><P><b>x</P></b><BR></BODY></html>",
        )) {
            myFixture.configureByText(name, text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            ReadAction.run<RuntimeException> {
                for (offset in text.indices) {
                    val iterator = myFixture.editor.highlighter.createIterator(offset)
                    val left = BraceMatchingUtil.isLBraceToken(iterator, text, myFixture.file.fileType)
                    val right = BraceMatchingUtil.isRBraceToken(iterator, text, myFixture.file.fileType)
                    if (!left && !right) continue
                    val oldIterator = myFixture.editor.highlighter.createIterator(offset)
                    val ownedIterator = myFixture.editor.highlighter.createIterator(offset)
                    val expected = BraceMatchingUtil.matchBrace(text, myFixture.file.fileType, oldIterator, left)
                    val actual = NativeBraceMatching.match(text, myFixture.file.fileType, ownedIterator, left) {}
                    assertThat(actual).describedAs("%s offset=%s", name, offset).isEqualTo(expected)
                    assertThat(terminal(ownedIterator)).isEqualTo(terminal(oldIterator))
                }
            }
        }
    }

    fun testOwnedMatchingPreservesLazyMatcherCallbackOrder() = verifyOwnedMatchingPreservesLazyMatcherCallbackOrder()

    private fun verifyOwnedMatchingPreservesLazyMatcherCallbackOrder() {
        myFixture.configureByText("Order.java", "class Order { void run() { call(([1)]); } }")
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
                val offset = myFixture.editor.document.text.indexOf('(')
                val chars = myFixture.editor.document.charsSequence
                val oldIterator = myFixture.editor.highlighter.createIterator(offset)
                BraceMatchingUtil.matchBrace(chars, myFixture.file.fileType, oldIterator, true)
                val expected = calls.toList()
                calls.clear()
                val ownedIterator = myFixture.editor.highlighter.createIterator(offset)
                NativeBraceMatching.match(chars, myFixture.file.fileType, ownedIterator, true) {}
                assertThat(calls).isEqualTo(expected)
                assertThat(terminal(ownedIterator)).isEqualTo(terminal(oldIterator))
            }
        } finally {
            LanguageBraceMatching.INSTANCE.removeExplicitExtension(JavaLanguage.INSTANCE, recording)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testEdtWriteCancelsAnActualOwnedScanBeforeReadActionRetry() =
        verifyEdtWriteCancelsAnActualOwnedScanBeforeReadActionRetry()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verifyEdtWriteCancelsAnActualOwnedScanBeforeReadActionRetry() {
        myFixture.configureByText("Write.java", "class Write { void run() {\n" + "call(1);\n".repeat(4000) + "} }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val editor = myFixture.editor
        val offset = editor.document.text.indexOf('{')
        val entered = CountDownLatch(1)
        val writeRequested = AtomicBoolean()
        val interrupted = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = scope.async {
                readAction {
                    var probes = 0
                    try {
                        NativeBraceMatching.match(
                            editor.document.charsSequence,
                            myFixture.file.fileType,
                            editor.highlighter.createIterator(offset),
                            true,
                        ) {
                            ProgressManager.checkCanceled()
                            if (++probes == 3) {
                                entered.countDown()
                                // This is a real scan checkpoint under read access, not off-read computation.
                                val deadline = System.nanoTime() + 10_000_000_000L
                                while (!writeRequested.get()) {
                                    ProgressManager.checkCanceled()
                                    check(System.nanoTime() < deadline) {
                                        "Mid-scan write cancellation watchdog expired"
                                    }
                                    Thread.yield()
                                }
                                ProgressManager.checkCanceled()
                            }
                        }
                    } catch (error: ProcessCanceledException) {
                        interrupted.incrementAndGet()
                        throw error
                    }
                }
            }
            PlatformTestUtil.waitWithEventsDispatching("Owned scan did not reach mid-traversal", {
                entered.count == 0L
            }, 10)
            ApplicationManager.getApplication().addApplicationListener(
                object : ApplicationListener {
                    override fun beforeWriteActionStart(action: Any) {
                        writeRequested.set(true)
                    }
                },
                testRootDisposable,
            )
            val interruptedBeforeWrite = interrupted.get()
            ApplicationManager.getApplication().runWriteAction { }
            assertThat(interrupted.get()).isGreaterThan(interruptedBeforeWrite)
            PlatformTestUtil.waitWithEventsDispatching("Owned scan retry did not finish", { worker.isCompleted }, 10)
            assertThat(worker.getCompleted()).isTrue()
        } finally {
            scope.cancel()
        }
    }

    fun testCancellationInsideMembershipAndRecoveryWithoutIteratorMovement() {
        myFixture.configureByText("Types.java", "class Types {}")
        val chars = "(".repeat(4096) + "[" + "(".repeat(4096) + "]"
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument(chars), project) as EditorEx
        val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(myFixture.file.fileType, project, null)
        editor.setHighlighter(LexerEditorHighlighter(checkNotNull(syntax), editor.colorsScheme))
        try {
            ReadAction.run<RuntimeException> {
                // The first checkpoint at the closing token is in contains; the seventeenth
                // is in recovery after membership has found the '[' beneath 4096 '(' tokens.
                for (stopAt in listOf(1, 17)) {
                    val iterator = editor.highlighter.createIterator(0)
                    var closingProbes = 0
                    assertThatThrownBy {
                        NativeBraceMatching.match(chars, myFixture.file.fileType, iterator, true) {
                            if (!iterator.atEnd() && iterator.start == chars.lastIndex && ++closingProbes == stopAt) {
                                throw TraversalCanceled()
                            }
                        }
                    }.isInstanceOf(TraversalCanceled::class.java)
                    assertThat(iterator.start).isEqualTo(chars.lastIndex)
                    assertThat(closingProbes).isEqualTo(stopAt)
                }
            }
        } finally {
            factory.releaseEditor(editor)
        }
    }

    private fun terminal(iterator: HighlighterIterator): List<Any?> =
        if (iterator.atEnd()) listOf(true) else listOf(false, iterator.start, iterator.end, iterator.tokenType)

    private fun assertContextParity(offset: Int, blockCursor: Boolean) {
        myFixture.editor.caretModel.moveToOffset(offset)
        val platform = BraceMatchingUtil.computeHighlightingAndNavigationContext(myFixture.editor, myFixture.file)
        val expected = platform?.let { NativeBraceContext.Context(it.currentBraceOffset(), it.navigationOffset()) }
        assertThat(NativeBraceContext.compute(myFixture.editor, myFixture.file, offset, blockCursor) {})
            .describedAs("caret=%s block=%s text=%s", offset, blockCursor, myFixture.editor.document.text)
            .isEqualTo(expected)
    }

    private class TraversalCanceled : RuntimeException()
}
