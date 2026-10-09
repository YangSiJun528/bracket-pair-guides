package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher
import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.pairing.BracketGroupId
import com.sijunyang.bracketpairguides.analysis.pairing.CapturedBracketTokens
import com.sijunyang.bracketpairguides.analysis.pairing.TokenGrammarTestAdapter
import com.sijunyang.bracketpairguides.analysis.pairing.TokenKind
import com.sijunyang.bracketpairguides.analysis.pairing.completeTable
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType

class BracketTokenCaptureTest : BasePlatformTestCase() {
    fun testOneAndIrregularTokenChunksMatchUninterruptedRecognition() {
        configure("{ [ ( ) ] }\n{ ( } ) [ ]") { character ->
            when (character) {
                '{' -> STRUCTURAL_OPEN
                '}' -> STRUCTURAL_CLOSE
                '(' -> OPEN
                ')' -> CLOSE
                '[' -> SECOND_OPEN
                ']' -> SECOND_CLOSE
                else -> OTHER
            }
        }
        withMatchers(LANGUAGE to ordinaryMatcher()) {
            val expected = uninterrupted()
            for (sizes in listOf(listOf(1), listOf(3, 1, 4, 2), listOf(512))) {
                assertThat(streamed(sizes).hasSameContent(expected, CancellationProbe {})).isTrue()
            }
        }
    }

    fun testEmptyAndDenseChunksAlternateWithoutLosingOccurrencesOrChangingSealedBatches() {
        for (chunkSize in listOf(512, 2_048)) {
            configure(
                "x".repeat(chunkSize) + "()".repeat(chunkSize / 2) + "x".repeat(chunkSize) + "()".repeat(chunkSize / 2),
            ) { character ->
                when (character) {
                    '(' -> OPEN
                    ')' -> CLOSE
                    else -> OTHER
                }
            }
            withMatchers(LANGUAGE to ordinaryMatcher()) {
                val capture = BracketTokenCapture(input())
                val empty = read { capture.capture(0, chunkSize) {} }
                val dense = read { capture.capture(empty.nextOffset, chunkSize) {} }
                val saved = (0 until dense.size).map { dense[it] }
                val nextEmpty = read { capture.capture(dense.nextOffset, chunkSize) {} }
                val nextDense = read { capture.capture(nextEmpty.nextOffset, chunkSize) {} }
                assertThat(empty.size).isZero()
                assertThat(nextEmpty.size).isZero()
                assertThat(dense.size).isEqualTo(chunkSize)
                assertThat(nextDense.size).isEqualTo(chunkSize)
                assertThat(nextDense.end).isTrue()
                assertThat((0 until dense.size).map { dense[it] }).containsExactlyElementsOf(saved)
                assertThat(streamed(listOf(chunkSize)).hasSameContent(uninterrupted(), CancellationProbe {})).isTrue()
            }
        }
    }

    fun testBracketFreeChunksBoundVisitedTokensAndFinishExplicitlyAtEof() {
        configure("abcdefghij") { OTHER }
        val capture = BracketTokenCapture(input())
        val first = read { capture.capture(0, 3) {} }
        assertThat(first.size).isZero()
        assertThat(first.visitedTokens).isEqualTo(3)
        assertThat(first.nextOffset).isEqualTo(3)
        assertThat(first.end).isFalse()
        val last = read { capture.capture(9, 3) {} }
        assertThat(last.visitedTokens).isEqualTo(1)
        assertThat(last.nextOffset).isEqualTo(10)
        assertThat(last.end).isTrue()
        val eof = read { capture.capture(10, 3) {} }
        assertThat(eof.visitedTokens).isZero()
        assertThat(eof.size).isZero()
        assertThat(eof.end).isTrue()
    }

    fun testMatchingUsesActualCallbackAndCaptureDoesNotQueryCompatibilityEagerly() {
        configure("()") { if (it == '(') OPEN else CLOSE }
        var compatibilityCalls = 0
        val matcher = object : Matcher(arrayOf(BracePair(OPEN, CLOSE, false))) {
            override fun isPairBraces(tokenType1: IElementType, tokenType2: IElementType): Boolean {
                compatibilityCalls++
                return false
            }
        }
        withMatchers(LANGUAGE to matcher) {
            val capture = BracketTokenCapture(input())
            val tokens = read { capture.capture(0, 10) {} }
            assertThat(tokens.size).isEqualTo(2)
            assertThat(compatibilityCalls).isZero()
            val query = PairingMachine.RuleRequest(tokens[0].group, tokens[0].kind, tokens[1].kind)
            assertThat(read { capture.matches(query) }).isFalse()
            assertThat(compatibilityCalls).isEqualTo(1)
        }
    }

    fun testLanguageIdentitySeparatesEqualNumericGroupsAcrossChunks() {
        configure("a b c d") { character ->
            when (character) {
                'a' -> OPEN
                'b' -> FOREIGN_OPEN
                'c' -> CLOSE
                'd' -> FOREIGN_CLOSE
                else -> OTHER
            }
        }
        withMatchers(
            LANGUAGE to Matcher(arrayOf(BracePair(OPEN, CLOSE, false))),
            FOREIGN_LANGUAGE to Matcher(arrayOf(BracePair(FOREIGN_OPEN, FOREIGN_CLOSE, false))),
        ) {
            val expected = uninterrupted()
            assertThat(expected.size()).isEqualTo(2)
            assertThat(streamed(listOf(1)).hasSameContent(expected, CancellationProbe {})).isTrue()
            val capture = BracketTokenCapture(input())
            val first = read { capture.capture(0, 1) {} }[0]
            val foreign = read { capture.capture(2, 1) {} }[0]
            assertThat(first.group.tokenGroup).isEqualTo(foreign.group.tokenGroup)
            assertThat(first.group.languageOrdinal).isNotEqualTo(foreign.group.languageOrdinal)
        }
    }

    fun testDisabledFamilyAvailabilityAccumulatesWithoutEmittingTokens() {
        configure("()") { if (it == '(') OPEN else CLOSE }
        withMatchers(LANGUAGE to ordinaryMatcher()) {
            val capture = BracketTokenCapture(input(setOf(LANGUAGE.id)))
            val first = read { capture.capture(0, 1) {} }
            val last = read { capture.capture(first.nextOffset, 1) {} }
            assertThat(first.size).isZero()
            assertThat(last.size).isZero()
            assertThat(last.matcherAvailability).isEqualTo(BraceMatcherAvailability.DISABLED)
        }
    }

    fun testRealLayeredIteratorRestartsInsideOneBaseToken() {
        val source = "([{}])()"
        myFixture.configureByText("LayeredCapture.txt", source)
        val editor = myFixture.editor as EditorEx
        val inner = CharacterSyntax({ character ->
            when (character) {
                '(' -> OPEN
                ')' -> CLOSE
                '[' -> SECOND_OPEN
                ']' -> SECOND_CLOSE
                '{' -> STRUCTURAL_OPEN
                '}' -> STRUCTURAL_CLOSE
                else -> OTHER
            }
        })
        val layered =
            LayeredLexerEditorHighlighter(CharacterSyntax({ LAYER_SEGMENT }, whole = true), editor.colorsScheme)
        layered.registerLayer(LAYER_SEGMENT, LayerDescriptor(inner, ""))
        editor.setHighlighter(layered)
        withMatchers(LANGUAGE to ordinaryMatcher()) {
            val expected = uninterrupted()
            assertThat(expected.size()).isEqualTo(4)
            assertThat(streamed(listOf(1)).hasSameContent(expected, CancellationProbe {})).isTrue()
            assertThat(streamed(listOf(2, 1, 3)).hasSameContent(expected, CancellationProbe {})).isTrue()
            val capture = BracketTokenCapture(input())
            val restarted = read { capture.capture(3, 1) {} }
            assertThat(restarted[0].offset).isEqualTo(3)
            assertThat(restarted.nextOffset).isEqualTo(4)
        }
    }

    fun testEvictedKindCanBeRecreatedWhileAnOlderPendingKindStillResolves() {
        val closeTypes = List(1_025) { IElementType("CAPTURE_CLOSE_$it", LANGUAGE) }
        val source = buildString {
            append('a')
            for (index in closeTypes.indices) append((0x1000 + index).toChar())
            append("a)")
        }
        configure(source) { character ->
            when (character) {
                'a' -> OPEN
                ')' -> CLOSE
                else -> closeTypes[character.code - 0x1000]
            }
        }
        val matcher = object : Matcher(arrayOf(BracePair(OPEN, CLOSE, false))) {
            override fun isRBraceToken(
                iterator: HighlighterIterator,
                fileText: CharSequence,
                fileType: FileType,
            ): Boolean = iterator.tokenType === CLOSE || closeTypes.contains(iterator.tokenType)
        }
        withMatchers(LANGUAGE to matcher) {
            val capture = BracketTokenCapture(input())
            val first = read { capture.capture(0, 1) {} }[0]
            var offset = 1
            repeat(closeTypes.size) {
                val batch = read { capture.capture(offset, 1) {} }
                offset = batch.nextOffset
            }
            val second = read { capture.capture(offset, 1) {} }[0]
            val close = read { capture.capture(offset + 1, 1) {} }[0]
            assertThat(first.kind).isNotSameAs(second.kind)
            assertThat(
                read {
                    capture.matches(PairingMachine.RuleRequest(first.group, first.kind, close.kind))
                },
            ).isTrue()
            assertThat(
                read {
                    capture.matches(PairingMachine.RuleRequest(second.group, second.kind, close.kind))
                },
            ).isTrue()
            assertThat(streamed(listOf(1)).hasSameContent(uninterrupted(), CancellationProbe {})).isTrue()
        }
    }

    fun testMismatchedIteratorDocumentMarksTheEntireSourceUnavailable() {
        configure("()") { if (it == '(') OPEN else CLOSE }
        val editor = myFixture.editor as EditorEx
        val original = LexerEditorHighlighter(CharacterSyntax({ if (it == '(') OPEN else CLOSE }), editor.colorsScheme)
        val foreign = EditorFactory.getInstance().createDocument("()")
        editor.setHighlighter(object : EditorHighlighter by original {
            override fun createIterator(startOffset: Int): HighlighterIterator {
                val iterator = original.createIterator(startOffset)
                return object : HighlighterIterator by iterator {
                    override fun getDocument(): Document = foreign
                }
            }
        })
        val capture = BracketTokenCapture(input())
        val batch = read { capture.capture(0, 1) {} }
        assertThat(batch.sourceAvailable).isFalse()
        assertThat(batch.end).isTrue()
        assertThat(batch.size).isZero()
        assertThat(batch.matcherAvailability).isEqualTo(BraceMatcherAvailability.UNDETERMINED)
    }

    fun testSealedBatchRevokesMutationAndEmptyDocumentHasAnAvailableSource() {
        configure("()") { if (it == '(') OPEN else CLOSE }
        withMatchers(LANGUAGE to ordinaryMatcher()) {
            val capture = BracketTokenCapture(input())
            val captured = read { capture.capture(0, 1) {} }
            val original = captured[0]
            val builder = CapturedBracketTokens.Builder(1)
            builder.append(
                original.kind, original.group, original.context, original.strictContext,
                original.role, original.structuralRole, original.offset, original.tokenLength, original.line,
            )
            val frozen = builder.seal(1, false, 1, captured.matcherAvailability)
            assertThatExceptionOfType(IllegalStateException::class.java).isThrownBy {
                builder.append(
                    original.kind, original.group, original.context, original.strictContext,
                    original.role, original.structuralRole, 999, original.tokenLength, original.line,
                )
            }
            assertThatExceptionOfType(IllegalStateException::class.java).isThrownBy {
                builder.seal(999, false, 1, captured.matcherAvailability)
            }
            assertThat(frozen[0]).isEqualTo(original)
        }
        configure("") { OTHER }
        val empty = read { BracketTokenCapture(input()).capture(0, 1) {} }
        assertThat(empty.sourceAvailable).isTrue()
        assertThat(empty.end).isTrue()
        assertThat(empty.size).isZero()
    }

    fun testStrictXmlContextNormalizationAndRecoverySurviveChunking() {
        configure("<A <b >a >B") { character ->
            when (character) {
                '<' -> OPEN
                '>' -> CLOSE
                else -> OTHER
            }
        }
        val matcher = object : Matcher(arrayOf(BracePair(OPEN, CLOSE, false))), XmlAwareBraceMatcher {
            override fun isStrictTagMatching(fileType: FileType, braceGroupId: Int): Boolean = true
            override fun areTagsCaseSensitive(fileType: FileType, braceGroupId: Int): Boolean = false
            override fun getTagName(text: CharSequence, iterator: HighlighterIterator): String? =
                (iterator.start + 1).takeIf { it < text.length }?.let { text[it].toString() }
        }
        withMatchers(LANGUAGE to matcher) {
            val capture = BracketTokenCapture(input())
            val first = read { capture.capture(0, 1) {} }[0]
            assertThat(first.context).isEqualTo("a")
            assertThat(first.strictContext).isTrue()
            val expected = uninterrupted()
            assertThat(expected.size()).isEqualTo(1)
            assertThat(streamed(listOf(1, 2)).hasSameContent(expected, CancellationProbe {})).isTrue()
        }
    }

    fun testCancellationIsCheckedWithinBracketFreeCapture() {
        configure("a".repeat(800)) { OTHER }
        val capture = BracketTokenCapture(input())
        var checks = 0
        assertThatExceptionOfType(CaptureCanceled::class.java).isThrownBy {
            read {
                capture.capture(0, 800) {
                    if (++checks == 5) throw CaptureCanceled()
                }
            }
        }
        assertThat(checks).isEqualTo(5)
    }

    private fun streamed(sizes: List<Int>): PairTable {
        val capture = BracketTokenCapture(input())
        val draft = PairTable.draft()
        val session = PairingMachine<TokenKind, BracketGroupId>()
            .newSession(draft, CancellationProbe {}, 50_000)
        var offset = 0
        var chunk = 0
        do {
            val batch = read { capture.capture(offset, sizes[chunk++ % sizes.size]) {} }
            for (index in 0 until batch.size) {
                var step = batch.begin(index, session)
                while (step == PairingMachine.Step.NEEDS_RULE) {
                    val request = session.ruleRequest()
                    step = session.resume(read { capture.matches(request) })
                }
                assertThat(step).isEqualTo(PairingMachine.Step.ACCEPTED)
            }
            offset = batch.nextOffset
        } while (!batch.end)
        return draft.freeze()
    }

    private fun input(disabled: Set<String> = emptySet()): AnalysisInput = read {
        AnalysisInput(myFixture.editor, myFixture.file.fileType, AnalysisCoverage(true, true, false), disabled)
    }

    private fun uninterrupted(): PairTable = read {
        TokenGrammarTestAdapter(myFixture.editor, myFixture.file.fileType, BraceLanguageCatalog()) { true }
            .recognize(EmptyProgressIndicator()).completeTable()
    }

    private fun configure(source: String, tokenFor: (Char) -> IElementType) {
        myFixture.configureByText("BracketCapture.txt", source)
        val editor = myFixture.editor as EditorEx
        editor.setHighlighter(LexerEditorHighlighter(CharacterSyntax(tokenFor), editor.colorsScheme))
    }

    private fun withMatchers(vararg entries: Pair<Language, PairedBraceMatcher>, action: () -> Unit) {
        for ((language, matcher) in entries) LanguageBraceMatching.INSTANCE.addExplicitExtension(language, matcher)
        try {
            action()
        } finally {
            for ((language, matcher) in entries.reversed()) {
                LanguageBraceMatching.INSTANCE.removeExplicitExtension(
                    language,
                    matcher,
                )
            }
        }
    }

    private fun ordinaryMatcher(): Matcher = Matcher(
        arrayOf(
            BracePair(OPEN, CLOSE, false),
            BracePair(SECOND_OPEN, SECOND_CLOSE, false),
            BracePair(STRUCTURAL_OPEN, STRUCTURAL_CLOSE, true),
        ),
    )

    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }

    private open class Matcher(private val pairs: Array<BracePair>) :
        PairedBraceMatcher,
        BraceMatcher {
        override fun getPairs(): Array<BracePair> = pairs
        override fun getBraceTokenGroupId(tokenType: IElementType): Int = 7
        override fun isLBraceToken(iterator: HighlighterIterator, fileText: CharSequence, fileType: FileType): Boolean =
            pairs.any { it.leftBraceType === iterator.tokenType }
        override fun isRBraceToken(iterator: HighlighterIterator, fileText: CharSequence, fileType: FileType): Boolean =
            pairs.any { it.rightBraceType === iterator.tokenType }
        override fun isPairBraces(tokenType1: IElementType, tokenType2: IElementType): Boolean =
            pairs.any { it.leftBraceType === tokenType1 && it.rightBraceType === tokenType2 }
        override fun isStructuralBrace(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = false
        override fun getOppositeBraceTokenType(type: IElementType): IElementType? = pairs.firstOrNull {
            it.leftBraceType === type || it.rightBraceType === type
        }?.let { if (it.leftBraceType === type) it.rightBraceType else it.leftBraceType }
        override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
            true
        override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
    }

    private class CharacterSyntax(private val tokenFor: (Char) -> IElementType, private val whole: Boolean = false) :
        SyntaxHighlighter {
        override fun getHighlightingLexer(): Lexer = CharacterTokens(tokenFor, whole)
        override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = emptyArray()
    }

    private class CharacterTokens(private val tokenFor: (Char) -> IElementType, private val whole: Boolean) :
        LexerBase() {
        private var buffer: CharSequence = ""
        private var endOffset = 0
        private var offset = 0
        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
            this.buffer = buffer
            this.endOffset = endOffset
            offset = startOffset
        }
        override fun getState(): Int = 0
        override fun getTokenType(): IElementType? = if (offset < endOffset) tokenFor(buffer[offset]) else null
        override fun getTokenStart(): Int = offset
        override fun getTokenEnd(): Int = if (whole) endOffset else (offset + 1).coerceAtMost(endOffset)
        override fun advance() {
            offset = tokenEnd
        }
        override fun getBufferSequence(): CharSequence = buffer
        override fun getBufferEnd(): Int = endOffset
    }

    private class CaptureCanceled : RuntimeException()

    private companion object {
        val LANGUAGE = object : Language("BOUNDED_CAPTURE_TEST") {}
        val FOREIGN_LANGUAGE = object : Language("BOUNDED_CAPTURE_FOREIGN_TEST") {}
        val OPEN = IElementType("CAPTURE_OPEN", LANGUAGE)
        val CLOSE = IElementType("CAPTURE_CLOSE", LANGUAGE)
        val SECOND_OPEN = IElementType("CAPTURE_SECOND_OPEN", LANGUAGE)
        val SECOND_CLOSE = IElementType("CAPTURE_SECOND_CLOSE", LANGUAGE)
        val STRUCTURAL_OPEN = IElementType("CAPTURE_STRUCTURAL_OPEN", LANGUAGE)
        val STRUCTURAL_CLOSE = IElementType("CAPTURE_STRUCTURAL_CLOSE", LANGUAGE)
        val FOREIGN_OPEN = IElementType("CAPTURE_FOREIGN_OPEN", FOREIGN_LANGUAGE)
        val FOREIGN_CLOSE = IElementType("CAPTURE_FOREIGN_CLOSE", FOREIGN_LANGUAGE)
        val OTHER = IElementType("CAPTURE_OTHER", Language.ANY)
        val LAYER_SEGMENT = IElementType("CAPTURE_LAYER_SEGMENT", Language.ANY)
    }
}
