package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.ide.highlighter.custom.CustomFileHighlighter
import com.intellij.ide.highlighter.custom.SyntaxTable
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
import com.intellij.openapi.fileTypes.impl.AbstractFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.system.measureTimeMillis

class DocumentBracketsTest : BasePlatformTestCase() {
    fun testUsesJavaLexerAndBraceDefinitions() {
        val source = """
            class Sample {
                void run() {
                    String ignored = "}";
                    if (ready()) {
                        call();
                    }
                }
            }
        """.trimIndent()
        myFixture.configureByText("Sample.java", source)

        val pairs = analyze(EmptyProgressIndicator())
        val stringBraceOffset = source.indexOf("\"}\"") + 1

        assertTrue(pairs.isNotEmpty())
        assertFalse(
            "A brace token inside a string must be excluded by the Java lexer",
            pairs.any { it.openOffset == stringBraceOffset || it.closeOffset == stringBraceOffset },
        )
        assertTrue(
            "The outer class braces should be paired",
            pairs.any {
                source[it.openOffset] == '{' &&
                    source[it.closeOffset] == '}' &&
                    it.openLine == 0 &&
                    it.closeLine == 7
            },
        )
    }

    fun testLongJavaFileHasDeterministicLinearScaleResults() {
        val methodCount = 2_000
        val source = largeJavaSource(methodCount)
        myFixture.configureByText("Large.java", source)

        lateinit var first: List<BracketPair>
        val firstElapsedMillis = measureTimeMillis {
            first = analyze(EmptyProgressIndicator())
        }
        val second = analyze(EmptyProgressIndicator())

        assertEquals(methodCount * PAIRS_PER_GENERATED_METHOD + 1, first.size)
        assertEquals(first, second)
        assertTrue(
            "Analyzing ${source.length} Java characters took ${firstElapsedMillis}ms",
            firstElapsedMillis < LARGE_ANALYSIS_LIMIT_MILLIS,
        )
        assertTrue(
            first.any { pair ->
                pair.openOffset == source.indexOf('{') &&
                    pair.closeOffset == source.lastIndex
            },
        )
    }

    fun testMalformedDeepJavaInputIgnoresUnrelatedClosersAndRecoversPairs() {
        val depth = 2_048
        val source = "(".repeat(depth) + "]".repeat(depth) + ")".repeat(depth)
        myFixture.configureByText("Malformed.java", source)

        val pairs = analyze(EmptyProgressIndicator())

        assertEquals(depth, pairs.size)
        val pairsByOpenOffset = pairs.associateBy(BracketPair::openOffset)
        assertEquals(depth, pairsByOpenOffset.size)
        repeat(depth) { openOffset ->
            val pair = checkNotNull(pairsByOpenOffset[openOffset])
            assertEquals(openOffset, pair.depth)
            assertEquals(source.lastIndex - openOffset, pair.closeOffset)
        }
        assertTrue(pairs.all { pair -> source[pair.openOffset] == '(' })
        assertTrue(pairs.all { pair -> source[pair.closeOffset] == ')' })
    }

    fun testMalformedRegularPairDoesNotCrossAJavaStructuralPair() {
        val source = "class Broken ( { ) }"
        myFixture.configureByText("Structural.java", source)

        val pairs = analyze(EmptyProgressIndicator())

        assertEquals(1, pairs.size)
        assertEquals(source.indexOf('{'), pairs.single().openOffset)
        assertEquals(source.indexOf('}'), pairs.single().closeOffset)
    }

    fun testLongAnalysisHonorsCancellationDuringTokenTraversal() {
        myFixture.configureByText("Canceled.java", largeJavaSource(1_000))
        val delegate = EmptyProgressIndicator()
        var cancellationChecks = 0
        val indicator = object : ProgressIndicator by delegate {
            override fun checkCanceled() {
                cancellationChecks++
                if (cancellationChecks == 3) throw ProcessCanceledException()
                delegate.checkCanceled()
            }
        }

        try {
            analyze(indicator)
            fail("Expected token traversal to be canceled")
        } catch (_: ProcessCanceledException) {
            assertEquals(3, cancellationChecks)
        }
    }

    fun testLegacyFileTypeMatcherIsNotARecognitionFallback() {
        myFixture.configureByText("Unsupported.xml", "<root><child/></root>")

        assertTrue(analyze(EmptyProgressIndicator()).isEmpty())
    }

    fun testRawCharactersWithoutALanguageMatcherAreUnsupported() {
        myFixture.configureByText("Unsupported.txt", "{[(content)]}")

        assertTrue(analyze(EmptyProgressIndicator()).isEmpty())
    }

    fun testUsesOfficialCustomFileTypeBracketTokens() {
        val source = "{ [ ( value ) ] }"
        myFixture.configureByText("Custom.txt", source)
        val syntaxTable = SyntaxTable().apply {
            isHasBraces = true
            isHasBrackets = true
            isHasParens = true
        }
        val customFileType = AbstractFileType(syntaxTable)
        (myFixture.editor as EditorEx).setHighlighter(
            LexerEditorHighlighter(
                CustomFileHighlighter(syntaxTable),
                myFixture.editor.colorsScheme,
            ),
        )

        val pairs = ReadAction.compute<List<BracketPair>, RuntimeException> {
            documentBrackets(customFileType)
                .pairs(EmptyProgressIndicator())
                .toBracketPairs()
        }

        assertEquals(3, pairs.size)
        assertEquals(
            listOf(
                Triple('{', '}', 0),
                Triple('[', ']', 1),
                Triple('(', ')', 2),
            ),
            pairs.sortedBy(BracketPair::openOffset).map { pair ->
                Triple(source[pair.openOffset], source[pair.closeOffset], pair.depth)
            },
        )
    }

    fun testCustomFileTypeCapabilityCanBeDisabled() {
        val source = "{ value }"
        myFixture.configureByText("DisabledCustom.txt", source)
        val syntaxTable = SyntaxTable().apply { isHasBraces = true }
        val customFileType = AbstractFileType(syntaxTable)
        (myFixture.editor as EditorEx).setHighlighter(
            LexerEditorHighlighter(
                CustomFileHighlighter(syntaxTable),
                myFixture.editor.colorsScheme,
            ),
        )

        val pairs = ReadAction.compute<List<BracketPair>, RuntimeException> {
            documentBrackets(
                fileType = customFileType,
                isLanguageEnabled = { capabilityId -> capabilityId != "TEXT" },
            ).pairs(EmptyProgressIndicator()).toBracketPairs()
        }

        assertTrue(pairs.isEmpty())
    }

    fun testDisabledMatcherFamilyIsExcludedFromFullAnalysis() {
        myFixture.configureByText(
            "Disabled.java",
            "class Disabled { void run() { call(); } }",
        )
        val capabilityId = checkNotNull(
            BraceLanguageCatalog().definitionFor(myFixture.file.language)?.capabilityId,
        )

        val pairs = ReadAction.compute<List<BracketPair>, RuntimeException> {
            documentBrackets(
                isLanguageEnabled = { id -> id != capabilityId },
            ).pairs(EmptyProgressIndicator()).toBracketPairs()
        }

        assertTrue(pairs.isEmpty())
    }

    fun testInheritedMatcherUsesTheHighestSharedBaseLanguageAsCapabilityOwner() {
        LanguageBraceMatching.INSTANCE.addExplicitExtension(DYNAMIC_LANGUAGE, ANGLE_PAIRS)
        try {
            assertEquals(
                DYNAMIC_LANGUAGE.id,
                BraceLanguageCatalog().definitionFor(DYNAMIC_DIALECT_LANGUAGE)?.capabilityId,
            )
        } finally {
            LanguageBraceMatching.INSTANCE.removeExplicitExtension(DYNAMIC_LANGUAGE, ANGLE_PAIRS)
        }
    }

    fun testUsesContextualBehaviorFromARegisteredLanguageMatcher() {
        val source = "a < b > T<x>"
        myFixture.configureByText("Dynamic.txt", source)
        (myFixture.editor as EditorEx).setHighlighter(
            LexerEditorHighlighter(DYNAMIC_SYNTAX_HIGHLIGHTER, myFixture.editor.colorsScheme),
        )
        val matcher = AngleBracketGrammar()
        LanguageBraceMatching.INSTANCE.addExplicitExtension(DYNAMIC_LANGUAGE, matcher)

        try {
            val pairs = analyze(EmptyProgressIndicator())
            val supportedOpen = source.lastIndexOf('<')

            assertEquals(1, pairs.size)
            assertEquals(supportedOpen, pairs.single().openOffset)
            assertEquals(source.lastIndexOf('>'), pairs.single().closeOffset)
            assertFalse(
                "Static pair metadata must not turn comparison operators into braces",
                pairs.any { it.openOffset == source.indexOf('<') },
            )
        } finally {
            LanguageBraceMatching.INSTANCE.removeExplicitExtension(DYNAMIC_LANGUAGE, matcher)
        }
    }

    private fun analyze(indicator: ProgressIndicator): List<BracketPair> {
        return ReadAction.compute<List<BracketPair>, RuntimeException> {
            documentBrackets().pairs(indicator).toBracketPairs()
        }
    }

    private fun documentBrackets(
        fileType: FileType = myFixture.file.fileType,
        isLanguageEnabled: (String) -> Boolean = { true },
    ): DocumentBrackets = DocumentBrackets(
        editor = myFixture.editor,
        fileType = fileType,
        languages = BraceLanguageCatalog(),
        isLanguageEnabled = isLanguageEnabled,
    )

    private fun largeJavaSource(methodCount: Int): String {
        return buildString {
            append("class Large {\n")
            repeat(methodCount) { index ->
                append("  void method")
                append(index)
                append("() { consume(new int[] { ")
                append(index)
                append(" }); }\n")
            }
            append('}')
        }
    }

    private companion object {
        const val PAIRS_PER_GENERATED_METHOD = 5
        const val LARGE_ANALYSIS_LIMIT_MILLIS = 15_000L

        val DYNAMIC_LANGUAGE = object : Language("BRACKET_PAIR_GUIDES_DYNAMIC_TEST") {}
        val DYNAMIC_DIALECT_LANGUAGE = object : Language(
            DYNAMIC_LANGUAGE,
            "BRACKET_PAIR_GUIDES_DYNAMIC_DIALECT_TEST",
        ) {}
        val LEFT_ANGLE = IElementType("DYNAMIC_LEFT_ANGLE", DYNAMIC_LANGUAGE)
        val RIGHT_ANGLE = IElementType("DYNAMIC_RIGHT_ANGLE", DYNAMIC_LANGUAGE)
        val OTHER = IElementType("DYNAMIC_OTHER", DYNAMIC_LANGUAGE)

        val ANGLE_PAIRS = object : PairedBraceMatcher {
            override fun getPairs(): Array<BracePair> =
                arrayOf(BracePair(LEFT_ANGLE, RIGHT_ANGLE, false))

            override fun isPairedBracesAllowedBeforeType(
                lbraceType: IElementType,
                contextType: IElementType?,
            ): Boolean = true

            override fun getCodeConstructStart(
                file: PsiFile,
                openingBraceOffset: Int,
            ): Int = openingBraceOffset
        }

        val DYNAMIC_SYNTAX_HIGHLIGHTER = object : SyntaxHighlighter {
            override fun getHighlightingLexer(): Lexer = CharacterTokens()

            override fun getTokenHighlights(
                tokenType: IElementType,
            ): Array<TextAttributesKey> = emptyArray()
        }
    }

    private class AngleBracketGrammar :
        PairedBraceMatcherAdapter(ANGLE_PAIRS, DYNAMIC_LANGUAGE) {
        override fun isLBraceToken(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = iterator.tokenType === LEFT_ANGLE &&
            iterator.start > 0 && fileText[iterator.start - 1] == 'T'

        override fun isRBraceToken(
            iterator: HighlighterIterator,
            fileText: CharSequence,
            fileType: FileType,
        ): Boolean = iterator.tokenType === RIGHT_ANGLE &&
            iterator.start > 0 && fileText[iterator.start - 1] == 'x'
    }

    private class CharacterTokens : LexerBase() {
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

        override fun getTokenType(): IElementType? {
            if (offset >= endOffset) return null
            return when (buffer[offset]) {
                '<' -> LEFT_ANGLE
                '>' -> RIGHT_ANGLE
                else -> OTHER
            }
        }

        override fun getTokenStart(): Int = offset

        override fun getTokenEnd(): Int = (offset + 1).coerceAtMost(endOffset)

        override fun advance() {
            if (offset < endOffset) offset++
        }

        override fun getBufferSequence(): CharSequence = buffer

        override fun getBufferEnd(): Int = endOffset
    }
}
