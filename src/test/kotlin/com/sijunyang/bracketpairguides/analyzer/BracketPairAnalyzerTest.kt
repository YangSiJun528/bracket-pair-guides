package com.sijunyang.bracketpairguides.analyzer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.system.measureTimeMillis

class BracketPairAnalyzerTest : BasePlatformTestCase() {
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

        val pairs = BracketPairAnalyzer(myFixture.editor).collect(EmptyProgressIndicator())
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
        assertEquals(source.indexOf('{'), first.first().openOffset)
        assertEquals(source.lastIndex, first.first().closeOffset)
    }

    fun testMalformedDeepJavaInputIgnoresUnrelatedClosersAndRecoversPairs() {
        val depth = 2_048
        val source = "(".repeat(depth) + "]".repeat(depth) + ")".repeat(depth)
        myFixture.configureByText("Malformed.java", source)

        val pairs = analyze(EmptyProgressIndicator())

        assertEquals(depth, pairs.size)
        assertEquals((0 until depth).toList(), pairs.map(BracketPair::openOffset))
        assertEquals((0 until depth).toList(), pairs.map(BracketPair::depth))
        assertTrue(pairs.all { pair -> source[pair.openOffset] == '(' })
        assertTrue(pairs.all { pair -> source[pair.closeOffset] == ')' })
        assertTrue(pairs.zipWithNext().all { (outer, inner) ->
            outer.openOffset < inner.openOffset && outer.closeOffset > inner.closeOffset
        })
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

    fun testXmlMatcherUsesCaseSensitiveTagNamesAndRecoversMalformedNesting() {
        val mismatched = "<Root></root>"
        myFixture.configureByText("Mismatched.xml", mismatched)
        assertFalse(
            analyze(EmptyProgressIndicator()).any { pair ->
                pair.openOffset == 0 && pair.closeOffset == mismatched.lastIndexOf('>')
            },
        )

        val nested = "<root><child/></root>"
        myFixture.configureByText("Nested.xml", nested)
        val nestedPairs = analyze(EmptyProgressIndicator())
        assertTrue(
            nestedPairs.any { pair ->
                pair.openOffset == 0 &&
                    pair.closeOffset == nested.lastIndexOf('>') &&
                    pair.depth == 0
            },
        )
        assertTrue(
            nestedPairs.any { pair ->
                pair.openOffset == nested.indexOf('<', startIndex = 1) &&
                    pair.closeOffset == nested.indexOf("/>") &&
                    pair.closeTokenLength == 2 &&
                    pair.depth == 1
            },
        )

        val malformed = "<root><dangling></root>"
        myFixture.configureByText("Malformed.xml", malformed)
        val malformedPairs = analyze(EmptyProgressIndicator())
        assertTrue(
            malformedPairs.any { pair ->
                pair.openOffset == 0 && pair.closeOffset == malformed.lastIndexOf('>')
            },
        )
        assertFalse(
            malformedPairs.any { pair ->
                pair.openOffset == malformed.indexOf("<dangling>")
            },
        )
    }

    fun testMarkdownFileTypeMatcherFindsLinkDelimiters() {
        val source = "[label](target)"
        myFixture.configureByText("Link.md", source)

        val pairs = analyze(EmptyProgressIndicator())

        assertTrue(
            pairs.any { pair ->
                pair.openOffset == source.indexOf('[') &&
                    pair.closeOffset == source.indexOf(']')
            },
        )
        assertTrue(
            pairs.any { pair ->
                pair.openOffset == source.indexOf('(') &&
                    pair.closeOffset == source.indexOf(')')
            },
        )
    }

    private fun analyze(indicator: ProgressIndicator): List<BracketPair> {
        return ReadAction.compute<List<BracketPair>, RuntimeException> {
            BracketPairAnalyzer(
                editor = myFixture.editor,
                fileType = myFixture.file.fileType,
            ).collect(indicator)
        }
    }

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
    }
}
