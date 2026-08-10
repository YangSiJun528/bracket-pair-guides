package com.sijunyang.bracketpairguides

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.TokenWindow
import com.sijunyang.bracketpairguides.analysis.requireSnapshot
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.highlighting.BracketGuideHighlightingPass
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

class RealWorldFormatRegressionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    fun testJavaSource() {
        verifyFixture("YAMLUtil.java", minimumPairCount = 250)
    }

    fun testKotlinSource() {
        verifyFixture("IconUtil.kt", minimumPairCount = 550)
    }

    fun testJsonDocument() {
        verifyFixture("PlatformIconMappings.json", minimumPairCount = 120)
    }

    fun testKotlinScript() {
        verifyFixture("loadVSCBundles.main.kts", minimumPairCount = 450)
    }

    override fun getTestDataPath(): String = "src/test/testData"

    private fun verifyFixture(fileName: String, minimumPairCount: Int) {
        val file = myFixture.configureByFile("real-world/$fileName")
        val editor = myFixture.editor
        val document = editor.document

        assertFalse(
            "$fileName must be recognized by its bundled language plugin",
            file.fileType === PlainTextFileType.INSTANCE,
        )

        val analysis = service<BracketAnalysis>()
        val first = analyze(analysis, file.fileType)
        val second = analyze(analysis, file.fileType)
        val fullRange = TextRange(0, document.textLength)
        val firstTokens = first.visibleTokens(fullRange, focusOffset = 0, limit = Int.MAX_VALUE)
        val secondTokens = second.visibleTokens(fullRange, focusOffset = 0, limit = Int.MAX_VALUE)
        val firstTokenValues = firstTokens.toValues()
        val secondTokenValues = secondTokens.toValues()
        val pairCount = firstTokens.size / TOKENS_PER_PAIR

        assertEquals(
            "$fileName analysis must be deterministic",
            firstTokenValues,
            secondTokenValues,
        )
        assertEquals(
            "$fileName must produce complete token pairs",
            0,
            firstTokens.size % TOKENS_PER_PAIR,
        )
        assertTrue(
            "$fileName should contain at least $minimumPairCount pairs, but had $pairCount",
            pairCount >= minimumPairCount,
        )
        firstTokenValues.forEachIndexed { index, token ->
            assertTrue("$fileName token $index has an invalid offset", token.offset >= 0)
            assertTrue("$fileName token $index is empty", token.length > 0)
            assertTrue(
                "$fileName token $index exceeds the document",
                token.offset.toLong() + token.length <= document.textLength,
            )
            assertTrue("$fileName token $index has a negative depth", token.depth >= 0)
            assertTrue(
                "$fileName token $index has an impossible depth",
                token.depth < pairCount,
            )
        }

        val pass = BracketGuideHighlightingPass(
            project,
            editor,
            file.fileType,
            file.virtualFile,
        )
        editor.caretModel.moveToOffset(firstTokens.offsetAt(0) + 1)
        inReadAction {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()

        val session = checkNotNull(EditorGuideSessions.get(editor))
        val coloredTokenCount = editor.observedBracketMarkup().tokenMarks.size
        assertTrue(
            "$fileName must not create more ranges than analyzed tokens",
            coloredTokenCount <= firstTokens.size,
        )
        assertEquals(
            "$fileName must activate exactly one custom guide renderer at the caret",
            1,
            editor.observedBracketMarkup().guideMarks.size,
        )
        assertEquals(
            "$fileName must leave optional active-pair symbol emphasis disabled",
            0,
            editor.observedBracketMarkup().activePairMarks.size,
        )

        val emphasized = BracketGuideSettings.getInstance().options.copy(
            showActivePairBorder = true,
            showActivePairBackground = true,
        )
        BracketGuideSettings.getInstance().replace(emphasized)
        session.updateOptions(
            emphasized,
            refreshColors = false,
        )

        val sampledOffsets = buildSet {
            val sections = 24
            repeat(sections + 1) { section ->
                add(
                    (document.textLength.toLong() * section / sections)
                        .toInt(),
                )
            }
            firstTokenValues.indices
                .step(maxOf(1, firstTokenValues.size / sections))
                .take(sections)
                .forEach { tokenIndex ->
                    add((firstTokenValues[tokenIndex].offset + 1).coerceAtMost(document.textLength))
                }
        }
        sampledOffsets.forEach { offset ->
            editor.caretModel.moveToOffset(offset)
            session.caretMoved()
            val expected = first.activePairAt(offset)
            val activeGuide = editor.observedBracketMarkup().guideMarks.singleOrNull()
            assertEquals(
                "$fileName chose the wrong active pair at offset $offset",
                expected,
                (activeGuide?.customRenderer as? BracketGuideDrawing)?.guide?.pair,
            )
            assertEquals(
                "$fileName must highlight two symbols exactly when a pair is active at $offset",
                if (expected == null) 0 else 2,
                editor.observedBracketMarkup().activePairMarks.size,
            )
        }
    }

    private fun analyze(analysis: BracketAnalysis, fileType: FileType): BracketSnapshot =
        inReadAction {
            analysis.analyze(
                AnalysisInput(
                    editor = myFixture.editor,
                    fileType = fileType,
                    coverage = AnalysisCoverage(
                        tokens = true,
                        activePair = true,
                        guidePosition = true,
                    ),
                    disabledLanguageIds = emptySet(),
                ),
                EmptyProgressIndicator(),
            ).requireSnapshot()
        }

    private fun TokenWindow.toValues(): List<TokenValue> = List(size) { index ->
        TokenValue(
            offset = offsetAt(index),
            length = lengthAt(index),
            depth = depthAt(index),
        )
    }

    private fun <T> inReadAction(action: () -> T): T {
        return ReadAction.compute<T, RuntimeException>(action)
    }

    private data class TokenValue(
        val offset: Int,
        val length: Int,
        val depth: Int,
    )

    private companion object {
        const val TOKENS_PER_PAIR = 2
    }
}
