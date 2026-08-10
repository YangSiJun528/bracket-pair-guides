package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.active.CaretBracketSearch
import com.sijunyang.bracketpairguides.analysis.ActivePairKnowledge
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher
import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BracketPairingParityTest : BasePlatformTestCase() {
    fun testDualInterfaceContextualMatcherHasFullAndActiveParity() {
        val source = "a < b > T<x>"
        configure(source) { character ->
            when (character) {
                '<' -> CONTEXT_LEFT
                '>' -> CONTEXT_RIGHT
                else -> OTHER
            }
        }
        val matcher = object : BraceGrammarFixture(
            arrayOf(BracePair(CONTEXT_LEFT, CONTEXT_RIGHT, false)),
        ) {
            override fun isLBraceToken(
                iterator: HighlighterIterator,
                fileText: CharSequence,
                fileType: FileType,
            ): Boolean = iterator.tokenType === CONTEXT_LEFT &&
                iterator.start > 0 && fileText[iterator.start - 1] == 'T'

            override fun isRBraceToken(
                iterator: HighlighterIterator,
                fileText: CharSequence,
                fileType: FileType,
            ): Boolean = iterator.tokenType === CONTEXT_RIGHT &&
                iterator.start > 0 && fileText[iterator.start - 1] == 'x'
        }

        withMatchers(CONTEXT_LANGUAGE to matcher) {
            val full = analyze()
            val active = resolve(source.indexOf('x') + 1)

            assertEquals(1, full.size)
            assertPairOffsets(full.single(), requireActivePair(active))
            assertEquals(source.lastIndexOf('<'), full.single().openOffset)
            assertEquals(source.lastIndexOf('>'), full.single().closeOffset)
        }
    }

    fun testStrictCaseInsensitiveTagContextHasFullAndActiveParity() {
        val source = "<A x >b y >a"
        configure(source) { character ->
            when (character) {
                '<' -> STRICT_TAG_LEFT
                '>' -> STRICT_TAG_RIGHT
                else -> OTHER
            }
        }
        val matcher = StrictTagGrammar()

        withMatchers(STRICT_TAG_LANGUAGE to matcher) {
            val full = analyze()
            val active = requireActivePair(resolve(source.indexOf('x') + 1))

            assertEquals(1, full.size)
            assertEquals(source.lastIndexOf('>'), full.single().closeOffset)
            assertPairOffsets(full.single(), active)
        }
    }

    fun testLayeredLanguagesWithTheSameNumericGroupRemainSeparate() {
        val source = "<x>"
        configure(source) { character ->
            when (character) {
                '<' -> LAYER_CROSS_LEFT
                '>' -> LAYER_CROSS_RIGHT
                else -> OTHER
            }
        }
        val pairs = arrayOf(BracePair(LAYER_CROSS_LEFT, LAYER_CROSS_RIGHT, false))
        val leftMatcher = BraceGrammarFixture(pairs, groupId = SHARED_GROUP)
        val rightMatcher = BraceGrammarFixture(pairs, groupId = SHARED_GROUP)

        withMatchers(
            LAYER_A_LANGUAGE to leftMatcher,
            LAYER_B_LANGUAGE to rightMatcher,
        ) {
            assertTrue(analyze().isEmpty())
            assertEquals(
                ActivePairKnowledge.Known(null),
                resolve(source.indexOf('x') + 1),
            )
        }
    }

    fun testLayeredLanguageGateChangesBothFullAndActiveToTheOuterPair() {
        val source = "{[x]}"
        configure(source) { character ->
            when (character) {
                '{' -> LAYER_A_LEFT
                '}' -> LAYER_A_RIGHT
                '[' -> LAYER_B_LEFT
                ']' -> LAYER_B_RIGHT
                else -> OTHER
            }
        }
        val matcherA = BraceGrammarFixture(
            arrayOf(BracePair(LAYER_A_LEFT, LAYER_A_RIGHT, false)),
            groupId = SHARED_GROUP,
        )
        val matcherB = BraceGrammarFixture(
            arrayOf(BracePair(LAYER_B_LEFT, LAYER_B_RIGHT, false)),
            groupId = SHARED_GROUP,
        )

        withMatchers(
            LAYER_A_LANGUAGE to matcherA,
            LAYER_B_LANGUAGE to matcherB,
        ) {
            val caretOffset = source.indexOf('x') + 1
            val enabledFull = analyze()
            val enabledActive = requireActivePair(resolve(caretOffset))

            assertEquals(2, enabledFull.size)
            assertPairOffsets(enabledFull.single { it.openOffset == 1 }, enabledActive)

            val disableLayerB = { capabilityId: String ->
                capabilityId != LAYER_B_LANGUAGE.id
            }
            val disabledFull = analyze(disableLayerB)
            val disabledActive = requireActivePair(resolve(caretOffset, disableLayerB))

            assertEquals(1, disabledFull.size)
            assertEquals(0, disabledFull.single().openOffset)
            assertPairOffsets(disabledFull.single(), disabledActive)
        }
    }

    fun testPureSymmetricPairHasFullAndActiveParity() {
        val source = "|x|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(SYMMETRIC, SYMMETRIC, false)),
        )

        withMatchers(SYMMETRIC_LANGUAGE to matcher) {
            val full = analyze()
            val active = resolve(source.indexOf('x') + 1)

            assertEquals(1, full.size)
            assertPairOffsets(full.single(), requireActivePair(active))
        }
    }

    fun testSymmetricCloseBeforeCaretIsNotReusedAsAnOpener() {
        val source = "|a| b |c|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(SYMMETRIC, SYMMETRIC, false)),
        )

        withMatchers(SYMMETRIC_LANGUAGE to matcher) {
            assertEquals(2, analyze().size)
            assertEquals(
                ActivePairKnowledge.Known(null),
                resolve(source.indexOf('b') + 1),
            )
        }
    }

    fun testSecondSymmetricPairUsesItsAuthoritativeOrientation() {
        val source = "|a| b |c|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(SYMMETRIC, SYMMETRIC, false)),
        )

        withMatchers(SYMMETRIC_LANGUAGE to matcher) {
            val full = analyze().single { pair -> pair.openOffset == source.lastIndexOf('|', 7) }
            val active = requireActivePair(resolve(source.indexOf('c') + 1))

            assertPairOffsets(full, active)
        }
    }

    fun testSymmetricOrientationReplayReturnsIncompleteWhenBudgetRunsOut() {
        val source = "x".repeat(520) + "|a|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(SYMMETRIC, SYMMETRIC, false)),
        )

        withMatchers(SYMMETRIC_LANGUAGE to matcher) {
            assertSame(
                ActivePairKnowledge.Unknown,
                resolve(source.indexOf('a') + 1),
            )
        }
    }

    fun testStructuralBoundaryBlocksTheSameMalformedPairInBothPaths() {
        val source = "({x)"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                '{' -> STRUCTURAL_LEFT
                '}' -> STRUCTURAL_RIGHT
                else -> OTHER
            }
        }
        val matcher = BraceGrammarFixture(
            arrayOf(
                BracePair(STRUCTURAL_REGULAR_LEFT, STRUCTURAL_REGULAR_RIGHT, false),
                BracePair(STRUCTURAL_LEFT, STRUCTURAL_RIGHT, true),
            ),
        )

        withMatchers(STRUCTURAL_LANGUAGE to matcher) {
            assertTrue(analyze().isEmpty())
            assertEquals(
                ActivePairKnowledge.Known(null),
                resolve(source.indexOf('x') + 1),
            )
        }
    }

    fun testEarlierStructuralContextMakesTheBoundedResultIncomplete() {
        val source = "{(x})"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                '{' -> STRUCTURAL_LEFT
                '}' -> STRUCTURAL_RIGHT
                else -> OTHER
            }
        }
        val matcher = BraceGrammarFixture(
            arrayOf(
                BracePair(STRUCTURAL_REGULAR_LEFT, STRUCTURAL_REGULAR_RIGHT, false),
                BracePair(STRUCTURAL_LEFT, STRUCTURAL_RIGHT, true),
            ),
        )

        withMatchers(STRUCTURAL_LANGUAGE to matcher) {
            val full = analyze()

            assertEquals(1, full.size)
            assertEquals(source.indexOf('{'), full.single().openOffset)
            assertSame(
                ActivePairKnowledge.Unknown,
                resolve(source.indexOf('x') + 1),
            )
        }
    }

    fun testUnmatchedStructuralCloserMakesTheBoundedResultIncomplete() {
        val source = "(x})"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                '}' -> STRUCTURAL_RIGHT
                else -> OTHER
            }
        }
        val matcher = BraceGrammarFixture(
            arrayOf(
                BracePair(STRUCTURAL_REGULAR_LEFT, STRUCTURAL_REGULAR_RIGHT, false),
                BracePair(STRUCTURAL_LEFT, STRUCTURAL_RIGHT, true),
            ),
        )

        withMatchers(STRUCTURAL_LANGUAGE to matcher) {
            val full = analyze()

            assertEquals(1, full.size)
            assertEquals(source.indexOf('('), full.single().openOffset)
            assertSame(
                ActivePairKnowledge.Unknown,
                resolve(source.indexOf('x') + 1),
            )
        }
    }

    fun testForeignStructuralCloserDoesNotInvalidateTheTrackedLanguage() {
        val source = "(x})"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                '}' -> LAYER_B_RIGHT
                else -> OTHER
            }
        }
        val regularMatcher = BraceGrammarFixture(
            arrayOf(
                BracePair(STRUCTURAL_REGULAR_LEFT, STRUCTURAL_REGULAR_RIGHT, false),
            ),
        )
        val foreignStructuralMatcher = BraceGrammarFixture(
            arrayOf(BracePair(LAYER_B_LEFT, LAYER_B_RIGHT, true)),
        )

        withMatchers(
            STRUCTURAL_LANGUAGE to regularMatcher,
            LAYER_B_LANGUAGE to foreignStructuralMatcher,
        ) {
            val full = analyze().single()
            val active = requireActivePair(resolve(source.indexOf('x') + 1))

            assertPairOffsets(full, active)
        }
    }

    fun testWellFormedStructuralNestingRemainsImmediate() {
        val source = "{(x)}"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                '{' -> STRUCTURAL_LEFT
                '}' -> STRUCTURAL_RIGHT
                else -> OTHER
            }
        }
        val matcher = BraceGrammarFixture(
            arrayOf(
                BracePair(STRUCTURAL_REGULAR_LEFT, STRUCTURAL_REGULAR_RIGHT, false),
                BracePair(STRUCTURAL_LEFT, STRUCTURAL_RIGHT, true),
            ),
        )

        withMatchers(STRUCTURAL_LANGUAGE to matcher) {
            val full = analyze().single { pair ->
                pair.openOffset == source.indexOf('(')
            }
            val active = requireActivePair(resolve(source.indexOf('x') + 1))

            assertPairOffsets(full, active)
        }
    }

    fun testSharedCloserRecoveryHasFullAndActiveParity() {
        val source = "bcqx"
        configure(source) { character ->
            when (character) {
                'a' -> SHARED_A
                'b' -> SHARED_B
                'c' -> SHARED_C
                'x' -> SHARED_X
                'y' -> SHARED_Y
                else -> OTHER
            }
        }
        val matcher = BraceGrammarFixture(
            arrayOf(
                BracePair(SHARED_A, SHARED_X, false),
                BracePair(SHARED_B, SHARED_X, false),
                BracePair(SHARED_C, SHARED_Y, false),
            ),
        )

        withMatchers(SHARED_LANGUAGE to matcher) {
            val full = analyze()
            val active = resolve(source.indexOf('x'))

            assertEquals(1, full.size)
            assertPairOffsets(full.single(), requireActivePair(active))
            assertEquals(source.indexOf('b'), full.single().openOffset)
            assertEquals(source.indexOf('x'), full.single().closeOffset)
        }
    }

    fun testTransitionBudgetIsSharedAcrossCandidateForwardAttempts() {
        val source = "(".repeat(300) + "x"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                else -> OTHER
            }
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(STRUCTURAL_REGULAR_LEFT, STRUCTURAL_REGULAR_RIGHT, false)),
        )

        withMatchers(STRUCTURAL_LANGUAGE to matcher) {
            assertSame(
                ActivePairKnowledge.Unknown,
                resolve(source.length),
            )
        }
    }

    private fun configure(
        source: String,
        tokenFor: (Char) -> IElementType,
    ) {
        myFixture.configureByText("PairingParity.txt", source)
        val editor = myFixture.editor as EditorEx
        editor.setHighlighter(
            LexerEditorHighlighter(
                CharacterSyntax(tokenFor),
                editor.colorsScheme,
            ),
        )
    }

    private fun analyze(
        isLanguageEnabled: (String) -> Boolean = { true },
    ): List<BracketPair> = ReadAction.compute<List<BracketPair>, RuntimeException> {
        DocumentBrackets(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            languages = BraceLanguageCatalog(),
            isLanguageEnabled = isLanguageEnabled,
        ).pairs(EmptyProgressIndicator()).toBracketPairs()
    }

    private fun resolve(
        caretOffset: Int,
        isLanguageEnabled: (String) -> Boolean = { true },
    ): ActivePairKnowledge = ReadAction.compute<
        ActivePairKnowledge,
        RuntimeException,
    > {
        CaretBracketSearch(
            fileType = myFixture.file.fileType,
            languages = BraceLanguageCatalog(),
            isLanguageEnabled = isLanguageEnabled,
        ).findInnermost(myFixture.editor, caretOffset)
    }

    private fun requireActivePair(
        resolution: ActivePairKnowledge,
    ): BracketPair {
        assertTrue(
            "Expected a complete active resolution, got $resolution",
            resolution is ActivePairKnowledge.Known,
        )
        return checkNotNull((resolution as ActivePairKnowledge.Known).pair)
    }

    private fun assertPairOffsets(expected: BracketPair, actual: BracketPair) {
        assertEquals(expected.openOffset, actual.openOffset)
        assertEquals(expected.openTokenLength, actual.openTokenLength)
        assertEquals(expected.closeOffset, actual.closeOffset)
        assertEquals(expected.closeTokenLength, actual.closeTokenLength)
    }

    private fun withMatchers(
        vararg entries: Pair<Language, PairedBraceMatcher>,
        action: () -> Unit,
    ) {
        for ((language, matcher) in entries) {
            LanguageBraceMatching.INSTANCE.addExplicitExtension(language, matcher)
        }
        try {
            action()
        } finally {
            for ((language, matcher) in entries.reversed()) {
                LanguageBraceMatching.INSTANCE.removeExplicitExtension(language, matcher)
            }
        }
    }

    private open class BraceGrammarFixture(
        private val registeredPairs: Array<BracePair>,
        private val groupId: Int = DEFAULT_GROUP,
    ) : PairedBraceMatcher, BraceMatcher {
        override fun getPairs(): Array<BracePair> = registeredPairs

        override fun getBraceTokenGroupId(tokenType: IElementType): Int = groupId

        override fun isLBraceToken(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = registeredPairs.any { pair ->
            pair.leftBraceType === iterator.tokenType
        }

        override fun isRBraceToken(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = registeredPairs.any { pair ->
            pair.rightBraceType === iterator.tokenType
        }

        override fun isPairBraces(
            tokenType1: IElementType,
            tokenType2: IElementType,
        ): Boolean = registeredPairs.any { pair ->
            pair.leftBraceType === tokenType1 && pair.rightBraceType === tokenType2
        }

        override fun isStructuralBrace(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = registeredPairs.any { pair ->
            pair.isStructural &&
                (pair.leftBraceType === iterator.tokenType ||
                    pair.rightBraceType === iterator.tokenType)
        }

        override fun getOppositeBraceTokenType(
            type: IElementType,
        ): IElementType? {
            for (pair in registeredPairs) {
                if (pair.leftBraceType === type) return pair.rightBraceType
                if (pair.rightBraceType === type) return pair.leftBraceType
            }
            return null
        }

        override fun isPairedBracesAllowedBeforeType(
            lbraceType: IElementType,
            contextType: IElementType?,
        ): Boolean = true

        override fun getCodeConstructStart(
            file: PsiFile,
            openingBraceOffset: Int,
        ): Int = openingBraceOffset
    }

    private class StrictTagGrammar : BraceGrammarFixture(
        arrayOf(BracePair(STRICT_TAG_LEFT, STRICT_TAG_RIGHT, false)),
    ), XmlAwareBraceMatcher {
        override fun isStrictTagMatching(fileType: FileType, braceGroupId: Int): Boolean = true

        override fun areTagsCaseSensitive(fileType: FileType, braceGroupId: Int): Boolean = false

        override fun getTagName(
            text: CharSequence,
            iterator: HighlighterIterator,
        ): String? {
            val nameOffset = iterator.start + 1
            return if (nameOffset < text.length) text[nameOffset].toString() else null
        }
    }

    private class CharacterSyntax(
        private val tokenFor: (Char) -> IElementType,
    ) : SyntaxHighlighter {
        override fun getHighlightingLexer(): Lexer = CharacterTokens(tokenFor)

        override fun getTokenHighlights(
            tokenType: IElementType,
        ): Array<TextAttributesKey> = emptyArray()
    }

    private class CharacterTokens(
        private val tokenFor: (Char) -> IElementType,
    ) : LexerBase() {
        private var buffer: CharSequence = ""
        private var endOffset: Int = 0
        private var offset: Int = 0

        override fun start(
            buffer: CharSequence,
            startOffset: Int,
            endOffset: Int,
            initialState: Int,
        ) {
            this.buffer = buffer
            this.endOffset = endOffset
            offset = startOffset
        }

        override fun getState(): Int = 0

        override fun getTokenType(): IElementType? =
            if (offset < endOffset) tokenFor(buffer[offset]) else null

        override fun getTokenStart(): Int = offset

        override fun getTokenEnd(): Int = (offset + 1).coerceAtMost(endOffset)

        override fun advance() {
            if (offset < endOffset) offset++
        }

        override fun getBufferSequence(): CharSequence = buffer

        override fun getBufferEnd(): Int = endOffset
    }

    private companion object {
        const val DEFAULT_GROUP = 7
        const val SHARED_GROUP = 41

        val OTHER_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_OTHER") {}
        val OTHER = IElementType("PARITY_OTHER", OTHER_LANGUAGE)

        val CONTEXT_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_CONTEXT") {}
        val CONTEXT_LEFT = IElementType("PARITY_CONTEXT_LEFT", CONTEXT_LANGUAGE)
        val CONTEXT_RIGHT = IElementType("PARITY_CONTEXT_RIGHT", CONTEXT_LANGUAGE)

        val STRICT_TAG_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_STRICT_TAG") {}
        val STRICT_TAG_LEFT = IElementType("PARITY_STRICT_TAG_LEFT", STRICT_TAG_LANGUAGE)
        val STRICT_TAG_RIGHT = IElementType("PARITY_STRICT_TAG_RIGHT", STRICT_TAG_LANGUAGE)

        val LAYER_A_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_LAYER_A") {}
        val LAYER_B_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_LAYER_B") {}
        val LAYER_CROSS_LEFT = IElementType("PARITY_LAYER_CROSS_LEFT", LAYER_A_LANGUAGE)
        val LAYER_CROSS_RIGHT = IElementType("PARITY_LAYER_CROSS_RIGHT", LAYER_B_LANGUAGE)
        val LAYER_A_LEFT = IElementType("PARITY_LAYER_A_LEFT", LAYER_A_LANGUAGE)
        val LAYER_A_RIGHT = IElementType("PARITY_LAYER_A_RIGHT", LAYER_A_LANGUAGE)
        val LAYER_B_LEFT = IElementType("PARITY_LAYER_B_LEFT", LAYER_B_LANGUAGE)
        val LAYER_B_RIGHT = IElementType("PARITY_LAYER_B_RIGHT", LAYER_B_LANGUAGE)

        val SYMMETRIC_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_SYMMETRIC") {}
        val SYMMETRIC = IElementType("PARITY_SYMMETRIC", SYMMETRIC_LANGUAGE)

        val STRUCTURAL_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_STRUCTURAL") {}
        val STRUCTURAL_REGULAR_LEFT =
            IElementType("PARITY_STRUCTURAL_REGULAR_LEFT", STRUCTURAL_LANGUAGE)
        val STRUCTURAL_REGULAR_RIGHT =
            IElementType("PARITY_STRUCTURAL_REGULAR_RIGHT", STRUCTURAL_LANGUAGE)
        val STRUCTURAL_LEFT = IElementType("PARITY_STRUCTURAL_LEFT", STRUCTURAL_LANGUAGE)
        val STRUCTURAL_RIGHT = IElementType("PARITY_STRUCTURAL_RIGHT", STRUCTURAL_LANGUAGE)

        val SHARED_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_SHARED") {}
        val SHARED_A = IElementType("PARITY_SHARED_A", SHARED_LANGUAGE)
        val SHARED_B = IElementType("PARITY_SHARED_B", SHARED_LANGUAGE)
        val SHARED_C = IElementType("PARITY_SHARED_C", SHARED_LANGUAGE)
        val SHARED_X = IElementType("PARITY_SHARED_X", SHARED_LANGUAGE)
        val SHARED_Y = IElementType("PARITY_SHARED_Y", SHARED_LANGUAGE)
    }
}
