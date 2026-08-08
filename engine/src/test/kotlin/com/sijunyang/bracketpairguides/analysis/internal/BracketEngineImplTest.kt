package com.sijunyang.bracketpairguides.analysis.internal

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.api.ActivePairRequest
import com.sijunyang.bracketpairguides.analysis.api.ActivePairResult
import com.sijunyang.bracketpairguides.analysis.api.AnalysisCapabilities
import com.sijunyang.bracketpairguides.analysis.api.AnalyzeRequest

class BracketEngineImplTest : BasePlatformTestCase() {
    fun testAnalyzePreservesRevisionAndAnswersOnlyFacadeQueries() {
        val source = """
            class Sample {
                void run() {
                    call();
                }
            }
        """.trimIndent()
        myFixture.configureByText("Sample.java", source)
        val request = request(
            AnalysisCapabilities(
                tokens = true,
                activePair = true,
                guidePosition = true,
            ),
        )

        val result = inReadAction {
            BracketEngineImpl().analyze(request, EmptyProgressIndicator())
        }

        assertSame(request.revision, result.revision)
        val tokens = result.visibleTokens(
            range = TextRange(0, source.length),
            focusOffset = source.indexOf("call"),
            limit = 100,
        )
        assertFalse(tokens.isCapped)
        assertTrue(tokens.size > 0)
        assertEquals(
            (0 until tokens.size).map(tokens::offsetAt).sorted(),
            (0 until tokens.size).map(tokens::offsetAt),
        )

        val activePair = checkNotNull(result.activePairAt(source.indexOf("call") + 2))
        assertEquals(source.indexOf('{', source.indexOf("run")), activePair.openOffset)
        assertEquals(source.indexOf('}', source.indexOf("call")), activePair.closeOffset)
        val guide = checkNotNull(result.guideFor(activePair))
        assertEquals(4, guide.guideColumn)
        assertEquals(activePair.closeLine, guide.anchorLine)
    }

    fun testResolveActivePairMapsTheBoundedResolverResult() {
        val source = "class Sample { }"
        myFixture.configureByText("Active.java", source)
        val engine = BracketEngineImpl()
        inReadAction {
            engine.analyze(
                request(
                    AnalysisCapabilities(
                        tokens = false,
                        activePair = true,
                        guidePosition = false,
                    ),
                ),
                EmptyProgressIndicator(),
            )
        }
        val activeRequest = ActivePairRequest(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            caretOffset = source.indexOf('{') + 1,
        )

        val resolution = inReadAction {
            engine.resolveActivePair(activeRequest)
        }

        val pair = (resolution as? ActivePairResult.Complete)?.pair
        assertNotNull("A tiny warmed token stream must fit the bounded lookup", pair)
        assertEquals(source.indexOf('{'), pair?.openOffset)
        assertEquals(source.indexOf('}'), pair?.closeOffset)
    }

    fun testResolveActivePairPreservesAuthoritativeMissAndBudgetExhaustion() {
        myFixture.configureByText("NoPair.java", "class NoPair { }")
        val engine = BracketEngineImpl()
        val authoritativeMiss = inReadAction {
            engine.resolveActivePair(
                ActivePairRequest(
                    editor = myFixture.editor,
                    fileType = myFixture.file.fileType,
                    caretOffset = 0,
                ),
            )
        }
        assertEquals(ActivePairResult.Complete(null), authoritativeMiss)

        val deepSource = buildString {
            append("class Budget { void run() { int value = 0;")
            repeat(600) { append("value++;") }
            append("int target = value; } }")
        }
        myFixture.configureByText("Budget.java", deepSource)
        val exhausted = inReadAction {
            engine.resolveActivePair(
                ActivePairRequest(
                    editor = myFixture.editor,
                    fileType = myFixture.file.fileType,
                    caretOffset = deepSource.indexOf("target") + 2,
                ),
            )
        }
        assertSame(ActivePairResult.Incomplete, exhausted)
    }

    fun testInstalledLanguagesReturnsStableUiReadyDtos() {
        val families = BracketEngineImpl().installedLanguages()

        assertTrue(families.isNotEmpty())
        assertEquals(families.map { family -> family.id }.sorted(), families.map { it.id })
        assertTrue(families.all { family -> family.id.isNotBlank() })
        assertTrue(families.all { family -> family.displayName.isNotBlank() })
        assertTrue(families.all { family -> family.memberDisplayNames.isNotEmpty() })
        val textFamily = families.single { family -> family.id == "TEXT" }
        assertTrue("Plain text" in textFamily.memberDisplayNames)
    }

    fun testAnalyzePropagatesPlatformCancellation() {
        myFixture.configureByText(
            "Canceled.java",
            "class Canceled { void run() { call(); } }",
        )
        val delegate = EmptyProgressIndicator()
        val canceled = object : ProgressIndicator by delegate {
            override fun checkCanceled(): Unit = throw ProcessCanceledException()
        }

        try {
            inReadAction {
                BracketEngineImpl().analyze(
                    request(
                        AnalysisCapabilities(
                            tokens = true,
                            activePair = true,
                            guidePosition = true,
                        ),
                    ),
                    canceled,
                )
            }
            fail("Expected facade analysis to propagate cancellation")
        } catch (_: ProcessCanceledException) {
            // Expected: the facade must not translate or suppress platform cancellation.
        }
    }

    fun testRequestsDefensivelyCopyDisabledLanguageIds() {
        myFixture.configureByText("Copy.java", "class Copy { }")
        val disabled = mutableSetOf("JAVA")
        val analyzeRequest = AnalyzeRequest(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            capabilities = AnalysisCapabilities(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
            disabledLanguageIds = disabled,
        )
        val activeRequest = ActivePairRequest(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            caretOffset = 1,
            disabledLanguageIds = disabled,
        )

        disabled.clear()
        disabled += "KOTLIN"

        assertEquals(setOf("JAVA"), analyzeRequest.disabledLanguageIds)
        assertEquals(setOf("JAVA"), activeRequest.disabledLanguageIds)
    }

    fun testRevisionChecksDocumentCapabilitiesAndLanguageSelection() {
        myFixture.configureByText("Revision.java", "class Revision { }")
        val capabilities = AnalysisCapabilities(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val revision = request(capabilities).revision
        val fileType = myFixture.file.fileType

        assertTrue(
            revision.satisfiesCurrent(myFixture.editor, fileType, capabilities, emptySet()),
        )
        assertFalse(
            revision.satisfiesCurrent(
                myFixture.editor,
                PlainTextFileType.INSTANCE,
                capabilities,
                emptySet(),
            ),
        )
        assertFalse(
            revision.satisfiesCurrent(
                myFixture.editor,
                fileType,
                capabilities.copy(activePair = true),
                emptySet(),
            ),
        )
        assertFalse(
            revision.satisfiesCurrent(
                myFixture.editor,
                fileType,
                capabilities,
                setOf("JAVA"),
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }

        assertFalse(
            revision.satisfiesCurrent(myFixture.editor, fileType, capabilities, emptySet()),
        )
    }

    fun testRevisionIgnoresTabSizeOnlyWhenGuidePositionsAreNotRequired() {
        myFixture.configureByText("Tabs.java", "class Tabs { }")
        val editor = myFixture.editor
        val originalTabSize = editor.settings.getTabSize(project)
        val tokenCapabilities = AnalysisCapabilities(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val tokenRevision = request(tokenCapabilities).revision
        val fileType = myFixture.file.fileType

        try {
            editor.settings.setTabSize(originalTabSize + 1)
            assertTrue(
                tokenRevision.satisfiesCurrent(
                    editor,
                    fileType,
                    tokenCapabilities,
                    emptySet(),
                ),
            )

            val guideCapabilities = AnalysisCapabilities(
                tokens = false,
                activePair = true,
                guidePosition = true,
            )
            val guideRevision = request(guideCapabilities).revision
            editor.settings.setTabSize(originalTabSize + 2)
            assertFalse(
                guideRevision.satisfiesCurrent(
                    editor,
                    fileType,
                    guideCapabilities,
                    emptySet(),
                ),
            )
        } finally {
            editor.settings.setTabSize(originalTabSize)
        }
    }

    fun testRevisionRejectsAReplacementHighlighter() {
        myFixture.configureByText("Highlighter.java", "class Highlighter { }")
        val editor = myFixture.editor
        val capabilities = AnalysisCapabilities(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val revision = request(capabilities).revision
        val fileType = myFixture.file.fileType

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )

        assertFalse(revision.satisfiesCurrent(editor, fileType, capabilities, emptySet()))
    }

    private fun request(capabilities: AnalysisCapabilities): AnalyzeRequest = AnalyzeRequest(
        editor = myFixture.editor,
        fileType = myFixture.file.fileType,
        capabilities = capabilities,
    )

    private fun <T> inReadAction(action: () -> T): T =
        ReadAction.compute<T, RuntimeException>(action)
}
