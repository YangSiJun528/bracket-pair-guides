package com.sijunyang.bracketpairguides

import com.sijunyang.bracketpairguides.analysis.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analysis.index.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.presentation.BracketGuideRenderer
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.highlighting.GuideLineHighlightingPass
import com.sijunyang.bracketpairguides.presentation.GUIDE_PAINT_STATE_KEY
import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RealWorldFormatRegressionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginOptions())
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

        val first = inReadAction {
            BracketPairAnalyzer(editor, file.fileType).collect(EmptyProgressIndicator())
        }
        val second = inReadAction {
            BracketPairAnalyzer(editor, file.fileType).collect(EmptyProgressIndicator())
        }

        assertEquals("$fileName analysis must be deterministic", first, second)
        assertTrue(
            "$fileName should contain at least $minimumPairCount pairs, but had ${first.size}",
            first.size >= minimumPairCount,
        )
        first.forEachIndexed { index, pair ->
            assertTrue("$fileName pair $index has an invalid opening offset", pair.openOffset >= 0)
            assertTrue("$fileName pair $index has an empty opening token", pair.openTokenLength > 0)
            assertTrue(
                "$fileName pair $index opening token exceeds the document",
                pair.openOffset + pair.openTokenLength <= document.textLength,
            )
            assertTrue(
                "$fileName pair $index closes before its opening token",
                pair.closeOffset >= pair.openOffset + pair.openTokenLength,
            )
            assertTrue("$fileName pair $index has an empty closing token", pair.closeTokenLength > 0)
            assertTrue(
                "$fileName pair $index closing token exceeds the document",
                pair.closeOffset + pair.closeTokenLength <= document.textLength,
            )
            assertTrue("$fileName pair $index has a negative depth", pair.depth >= 0)
            assertTrue(
                "$fileName pair $index has an impossible depth",
                pair.depth < first.size,
            )
            assertEquals(
                "$fileName pair $index has an incorrect opening line",
                document.getLineNumber(pair.openOffset),
                pair.openLine,
            )
            assertEquals(
                "$fileName pair $index has an incorrect closing line",
                document.getLineNumber(pair.closeOffset),
                pair.closeLine,
            )
            assertTrue(
                "$fileName pair $index closes on a line before it opens",
                pair.openLine <= pair.closeLine,
            )
        }

        val pass = GuideLineHighlightingPass(project, editor, file.fileType)
        editor.caretModel.moveToOffset(first.first().openOffset + 1)
        inReadAction {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()

        val session = checkNotNull(EditorGuideSession.get(editor))
        val coloredTokenCount = session.tokenDecorations.entries.count {
            it.colorKey in BracketColorPalette.LEVEL_KEYS
        }
        assertTrue(
            "$fileName must not create more than two token ranges per pair",
            coloredTokenCount <= first.size * 2,
        )
        assertEquals(
            "$fileName must activate exactly one custom guide renderer at the caret",
            1,
            if (session.activeGuide?.customRenderer === BracketGuideRenderer) 1 else 0,
        )
        assertEquals(
            "$fileName must leave optional active-pair symbol emphasis disabled",
            0,
            session.activePairHighlights.size,
        )

        val emphasized = PluginSettings.getInstance().options.copy(
            showActivePairBorder = true,
            showActivePairBackground = true,
        )
        PluginSettings.getInstance().replace(emphasized)
        session.updateOptions(emphasized)

        val activeIndex = ActiveBracketPairIndex.build(first)
        val sampledOffsets = buildSet {
            val sections = 24
            repeat(sections + 1) { section ->
                add(
                    (document.textLength.toLong() * section / sections)
                        .toInt(),
                )
            }
            first.indices
                .step(maxOf(1, first.size / sections))
                .take(sections)
                .forEach { pairIndex ->
                    add(first[pairIndex].openOffset + 1)
                }
        }
        sampledOffsets.forEach { offset ->
            editor.caretModel.moveToOffset(offset)
            session.caretMoved()
            val expected = first.getOrNull(activeIndex.activePairIndex(offset))
            val activeGuide = session.activeGuide
            assertEquals(
                "$fileName chose the wrong active pair at offset $offset",
                expected,
                activeGuide?.getUserData(GUIDE_PAINT_STATE_KEY)?.guide?.pair,
            )
            assertEquals(
                "$fileName must highlight two symbols exactly when a pair is active at $offset",
                if (expected == null) 0 else 2,
                session.activePairHighlights.size,
            )
        }
    }

    private fun <T> inReadAction(action: () -> T): T {
        return ReadAction.compute<T, RuntimeException>(action)
    }
}
