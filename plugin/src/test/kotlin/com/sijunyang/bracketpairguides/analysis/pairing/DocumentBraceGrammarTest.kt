package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
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
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import javax.swing.Icon

class DocumentBraceGrammarTest : BasePlatformTestCase() {
    fun testDualInterfaceMatcherHonorsContextualCallbacks() {
        val source = "a < b > T<x>"
        configure(source) { character ->
            when (character) {
                '<' -> CONTEXT_LEFT
                '>' -> CONTEXT_RIGHT
                else -> OTHER
            }
        }
        val pairs = BraceGrammarFixture(
            arrayOf(BracePair(CONTEXT_LEFT, CONTEXT_RIGHT, false)),
        )
        val matcher = object : PairedBraceMatcherAdapter(pairs, CONTEXT_LANGUAGE) {
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

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isEqualTo(source.lastIndexOf('<'))
            assertThat(full.single().closeOffset).isEqualTo(source.lastIndexOf('>'))
        }
    }

    fun testPairedMatcherUsesDeclaredStructuralTopology() {
        val source = "{x}"
        configure(source) { character ->
            when (character) {
                '{' -> STRUCTURAL_LEFT
                '}' -> STRUCTURAL_RIGHT
                else -> OTHER
            }
        }
        var structuralCallbackCount = 0
        val declaredMatcher = BraceGrammarFixture(
            arrayOf(BracePair(STRUCTURAL_LEFT, STRUCTURAL_RIGHT, true)),
        )
        val matcher = object : PairedBraceMatcherAdapter(
            declaredMatcher,
            STRUCTURAL_LANGUAGE,
        ) {
            override fun isStructuralBrace(
                iterator: HighlighterIterator,
                fileText: CharSequence,
                fileType: FileType,
            ): Boolean {
                structuralCallbackCount++
                return false
            }
        }

        withMatchers(STRUCTURAL_LANGUAGE to matcher) {
            assertThat(analyze()).hasSize(1)
            assertThat(structuralCallbackCount).isZero()
        }
    }

    fun testStrictTagContextIsCaseInsensitiveWhenTheMatcherSaysSo() {
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

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isEqualTo(source.indexOf('<'))
            assertThat(full.single().closeOffset).isEqualTo(source.lastIndexOf('>'))
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
            assertThat(analyze()).isEmpty()
        }
    }

    fun testLayeredLanguageGateLeavesTheEnabledOuterPair() {
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
            val enabledFull = analyze()

            assertThat(enabledFull).hasSize(2)
            assertThat(enabledFull).anySatisfy { pair ->
                assertThat(pair.openOffset).isEqualTo(1)
            }

            val disableLayerB = { capabilityId: String ->
                capabilityId != LAYER_B_LANGUAGE.id
            }
            val disabledFull = analyze(disableLayerB)

            assertThat(disabledFull).hasSize(1)
            assertThat(disabledFull.single().openOffset).isZero()
        }
    }

    fun testPureSymmetricPairClosesBeforeOpeningAgain() {
        val source = "|x|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(SYMMETRIC, SYMMETRIC, false)),
        )

        withMatchers(SYMMETRIC_LANGUAGE to matcher) {
            val full = analyze()

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isZero()
            assertThat(full.single().closeOffset).isEqualTo(2)
        }
    }

    fun testLegacyFileTypeMatcherSupportsPureSymmetricPairs() {
        val source = "|x|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = LegacyBraceGrammarFixture(
            listOf(SYMMETRIC to SYMMETRIC),
        )

        withLegacyMatcher(LEGACY_FILE_TYPE, matcher) {
            val full = analyze(fileType = LEGACY_FILE_TYPE)

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isZero()
            assertThat(full.single().closeOffset).isEqualTo(2)
        }
    }

    fun testSymmetricCloseIsNotReusedAsAnOpener() {
        val source = "|a| b |c|"
        configure(source) { character ->
            if (character == '|') SYMMETRIC else OTHER
        }
        val matcher = BraceGrammarFixture(
            arrayOf(BracePair(SYMMETRIC, SYMMETRIC, false)),
        )

        withMatchers(SYMMETRIC_LANGUAGE to matcher) {
            assertThat(
                analyze().map(BracketPair::openOffset),
            ).containsExactly(0, 6)
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
            val second = analyze().single { pair -> pair.openOffset == 6 }

            assertThat(second.closeOffset).isEqualTo(8)
        }
    }

    fun testStructuralBoundaryBlocksAMalformedRegularPair() {
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
            assertThat(analyze()).isEmpty()
        }
    }

    fun testLegacyMatcherUsesOccurrenceBasedStructuralBraceCallbacks() {
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
        val matcher = object : LegacyBraceGrammarFixture(
            listOf(
                STRUCTURAL_REGULAR_LEFT to STRUCTURAL_REGULAR_RIGHT,
                STRUCTURAL_LEFT to STRUCTURAL_RIGHT,
            ),
        ) {
            override fun isStructuralBrace(
                iterator: HighlighterIterator,
                fileText: CharSequence,
                fileType: FileType,
            ): Boolean = iterator.tokenType === STRUCTURAL_LEFT ||
                iterator.tokenType === STRUCTURAL_RIGHT
        }

        withLegacyMatcher(LEGACY_FILE_TYPE, matcher) {
            assertThat(analyze(fileType = LEGACY_FILE_TYPE)).isEmpty()
        }
    }

    fun testLegacyMatcherKeepsTokenLanguagesSeparate() {
        val source = "<x>"
        configure(source) { character ->
            when (character) {
                '<' -> LAYER_CROSS_LEFT
                '>' -> LAYER_CROSS_RIGHT
                else -> OTHER
            }
        }
        val matcher = LegacyBraceGrammarFixture(
            listOf(LAYER_CROSS_LEFT to LAYER_CROSS_RIGHT),
            groupId = SHARED_GROUP,
        )

        withLegacyMatcher(LEGACY_FILE_TYPE, matcher) {
            assertThat(analyze(fileType = LEGACY_FILE_TYPE)).isEmpty()
        }
    }

    fun testLegacyMatcherUsesHostLanguageAsCapabilityOwner() {
        val source = "(x)"
        configure(source) { character ->
            when (character) {
                '(' -> STRUCTURAL_REGULAR_LEFT
                ')' -> STRUCTURAL_REGULAR_RIGHT
                else -> OTHER
            }
        }
        val matcher = LegacyBraceGrammarFixture(
            listOf(STRUCTURAL_REGULAR_LEFT to STRUCTURAL_REGULAR_RIGHT),
        )

        withLegacyMatcher(LEGACY_FILE_TYPE, matcher) {
            val full = analyze(fileType = LEGACY_FILE_TYPE)
            val disabled = analyze(
                isLanguageEnabled = { capabilityId ->
                    capabilityId != STRUCTURAL_LANGUAGE.id
                },
                fileType = LEGACY_FILE_TYPE,
            )

            assertThat(full).hasSize(1)
            assertThat(disabled).isEmpty()
        }
    }

    fun testStructuralCloseRecoversPastARegularOpen() {
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

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isEqualTo(source.indexOf('{'))
            assertThat(full.single().closeOffset).isEqualTo(source.indexOf('}'))
        }
    }

    fun testUnmatchedStructuralCloserDoesNotConsumeARegularOpen() {
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

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isEqualTo(source.indexOf('('))
            assertThat(full.single().closeOffset).isEqualTo(source.indexOf(')'))
        }
    }

    fun testForeignStructuralCloserDoesNotInvalidateAnotherLanguage() {
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

            assertThat(full.openOffset).isEqualTo(source.indexOf('('))
            assertThat(full.closeOffset).isEqualTo(source.indexOf(')'))
        }
    }

    fun testWellFormedStructuralNestingRemainsPaired() {
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
            val full = analyze()
            val regular = full.single { pair ->
                pair.openOffset == source.indexOf('(')
            }

            assertThat(full).hasSize(2)
            assertThat(regular.closeOffset).isEqualTo(source.indexOf(')'))
        }
    }

    fun testSharedCloserRecoversTheCompatibleOpen() {
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

            assertThat(full).hasSize(1)
            assertThat(full.single().openOffset).isEqualTo(source.indexOf('b'))
            assertThat(full.single().closeOffset).isEqualTo(source.indexOf('x'))
        }
    }

    private fun configure(
        source: String,
        tokenFor: (Char) -> IElementType,
    ) {
        myFixture.configureByText("DocumentBraceGrammar.txt", source)
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
        fileType: FileType = myFixture.file.fileType,
    ): List<BracketPair> = ReadAction.compute<List<BracketPair>, RuntimeException> {
        DocumentBrackets(
            editor = myFixture.editor,
            fileType = fileType,
            languages = BraceLanguageCatalog(),
            isLanguageEnabled = isLanguageEnabled,
        ).recognize(EmptyProgressIndicator()).completeTable().toBracketPairs()
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

    private fun withLegacyMatcher(
        fileType: FileType,
        matcher: BraceMatcher,
        action: () -> Unit,
    ) {
        val extension = FileTypeExtensionPoint(fileType.name, matcher)
        val extensionPoint = BraceMatcher.EP_NAME.point
        extension.pluginDescriptor = extensionPoint.pluginDescriptor
        extensionPoint.registerExtension(
            extension,
            extensionPoint.pluginDescriptor,
            testRootDisposable,
        )
        action()
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

    private open class LegacyBraceGrammarFixture(
        private val registeredPairs: List<Pair<IElementType, IElementType>>,
        private val groupId: Int = DEFAULT_GROUP,
    ) : BraceMatcher {
        override fun getBraceTokenGroupId(tokenType: IElementType): Int = groupId

        override fun isLBraceToken(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = registeredPairs.any { (left, _) ->
            left === iterator.tokenType
        }

        override fun isRBraceToken(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = registeredPairs.any { (_, right) ->
            right === iterator.tokenType
        }

        override fun isPairBraces(
            tokenType1: IElementType,
            tokenType2: IElementType,
        ): Boolean = registeredPairs.any { (left, right) ->
            left === tokenType1 && right === tokenType2
        }

        override fun isStructuralBrace(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = false

        override fun getOppositeBraceTokenType(type: IElementType): IElementType? {
            for ((left, right) in registeredPairs) {
                if (left === type) return right
                if (right === type) return left
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

        val LEGACY_FILE_TYPE = object : LanguageFileType(STRUCTURAL_LANGUAGE) {
            override fun getName(): String = "BRACKET_PAIRING_LEGACY_TEST"

            override fun getDescription(): String = "Bracket pairing legacy matcher test"

            override fun getDefaultExtension(): String = "legacy-braces"

            override fun getIcon(): Icon? = null
        }

        val SHARED_LANGUAGE = object : Language("BRACKET_PAIRING_PARITY_SHARED") {}
        val SHARED_A = IElementType("PARITY_SHARED_A", SHARED_LANGUAGE)
        val SHARED_B = IElementType("PARITY_SHARED_B", SHARED_LANGUAGE)
        val SHARED_C = IElementType("PARITY_SHARED_C", SHARED_LANGUAGE)
        val SHARED_X = IElementType("PARITY_SHARED_X", SHARED_LANGUAGE)
        val SHARED_Y = IElementType("PARITY_SHARED_Y", SHARED_LANGUAGE)
    }
}
