package com.sijunyang.bracketpairguides.ui.measurement

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.ui.editor.EditorGuide
import com.sijunyang.bracketpairguides.ui.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.ui.editor.highlighting.NativeGuideAdvisory
import com.sijunyang.bracketpairguides.ui.policy.EditorActivity
import com.sijunyang.bracketpairguides.ui.policy.EditorPresentationPolicy
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.work.GuideWorkFactory

/**
 * Test-only SDK measurement adapter. Activity is explicitly active in headless fixtures;
 * this measures actual UI markup creation, not Swing visibility, focus, or screen paint.
 * No runtime/core implementation is visible to this module's compiler.
 */
class UiSdkMeasurementSession(editor: Editor, factory: GuideWorkFactory, mode: String) : AutoCloseable {
    private var editor: Editor? = editor
    private var guide: EditorGuide?
    private var started = false

    init {
        assertEdt()
        val preferences = BracketGuidePreferences(showActivePairBorder = true)
        val capabilities = EditorSurfaceClassifier.capabilities(editor)
        val expected = when (mode) {
            "all" -> AnalysisCoverage(tokens = true, activePair = true, guidePosition = true)
            "tokens" -> AnalysisCoverage(tokens = true, activePair = false, guidePosition = false)
            else -> error("Unknown shared SDK execution mode: $mode")
        }
        check(EditorPresentationPolicy.resolve(capabilities, preferences, EditorActivity.ACTIVE).analysis == expected) {
            "Actual editor surface does not match execution mode $mode: ${editor.editorKind}/$capabilities"
        }
        guide = EditorGuide(editor, preferences, capabilities, EditorActivity.ACTIVE, NativeGuideAdvisory(), factory)
    }

    fun request() {
        assertEdt()
        val current = checkNotNull(guide) { "Measurement session is closed" }
        if (started) {
            current.wakeUp()
        } else {
            started = true
            current.start()
        }
    }

    /** Shared unchanged-caret callback workload; no fake caret transition is introduced. */
    fun refresh() {
        assertEdt()
        checkNotNull(guide) { "Measurement session is closed" }.caretMoved()
    }

    /** Counts live plugin effects using public SDK markup only, without allocating a result list. */
    fun markupCount(): Int {
        assertEdt()
        val current = editor ?: return 0
        return current.markupModel.allHighlighters.count { owned(it, current) }
    }

    /** SDK resource identity inspection happens outside the timed workload. */
    fun markup(): List<Any> {
        assertEdt()
        val current = editor ?: return emptyList()
        return current.markupModel.allHighlighters.filter { owned(it, current) }
    }

    private fun owned(highlighter: RangeHighlighter, current: Editor): Boolean {
        if (!highlighter.isValid) return false
        if (highlighter.customRenderer?.javaClass?.name
                ?.startsWith("com.sijunyang.bracketpairguides.ui.presentation.") == true ||
            highlighter.textAttributesKey?.externalName
                ?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true
        ) {
            return true
        }
        // The isolated measurement editor has no unrelated ELEMENT_UNDER_CARET markup.
        val attributes = highlighter.getTextAttributes(current.colorsScheme)
        return highlighter.layer == HighlighterLayer.ELEMENT_UNDER_CARET &&
            attributes?.effectType == EffectType.BOXED
    }

    override fun close() {
        assertEdt()
        val current = guide
        guide = null
        editor = null
        current?.close()
    }

    private fun assertEdt() = ApplicationManager.getApplication().assertIsDispatchThread()
}
