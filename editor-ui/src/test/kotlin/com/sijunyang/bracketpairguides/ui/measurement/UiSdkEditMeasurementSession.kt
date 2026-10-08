package com.sijunyang.bracketpairguides.ui.measurement

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.sijunyang.bracketpairguides.ui.editor.EditorGuides
import com.sijunyang.bracketpairguides.ui.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.ui.editor.highlighting.NativeGuideAdvisory
import com.sijunyang.bracketpairguides.ui.policy.EditorActivity
import com.sijunyang.bracketpairguides.ui.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.ui.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.ui.work.GuideWorkFactory

/**
 * Exclusive test-host composition for actual document-event hide and repair measurement.
 * Headless activity is explicitly ACTIVE; this observes real SDK markup, not screen paint.
 * The common fixture writes the actual document; this helper never schedules repair itself.
 */
class UiSdkEditMeasurementSession(editor: Editor, factory: GuideWorkFactory) : AutoCloseable {
    private var editor: Editor? = editor
    private var connected = false
    private val advisory: NativeGuideAdvisory
    private val settings: BracketGuideSettings
    private val previousOptions: BracketGuidePreferences

    init {
        assertEdt()
        check(EditorSurfaceClassifier.capabilities(editor) == EditorCapabilities.MAIN) {
            "Edit-restoration workload requires an actual main editor"
        }
        UiSdkGuideIsolation.assertNoAttachments()
        settings = BracketGuideSettings.getInstance()
        previousOptions = settings.options
        advisory = NativeGuideAdvisory()
        // Reject foreign composition without mutating settings or disconnecting its factory.
        try {
            EditorGuides.connect(factory, advisory)
        } catch (failure: Throwable) {
            advisory.dispose()
            throw failure
        }
        connected = true
        try {
            // Initial setup and acceptance occur outside the timed document edit.
            // Setup-only persisted-state seeding matches the baseline fixture. Actual Settings
            // Apply and native coordinator behavior are validated separately by Driver.
            settings.loadState(BracketGuidePreferences(showActivePairBorder = true))
            EditorGuides.attach(editor)
            val owner = checkNotNull(EditorGuides.get(editor)) { "Actual editor attachment failed" }
            owner.updateSurface(EditorSurfaceClassifier.capabilities(editor), EditorActivity.ACTIVE)
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun markup(): List<Any> {
        assertEdt()
        val current = editor ?: return emptyList()
        return current.markupModel.allHighlighters.filter { owned(it, current) }
    }

    fun markupCount(): Int {
        assertEdt()
        val current = editor ?: return 0
        return current.markupModel.allHighlighters.count { owned(it, current) }
    }

    /** Actual current renderer geometry, copied only outside timed edit callbacks. */
    fun visibleGuide(): UiObservedGuide? {
        assertEdt()
        val current = editor ?: return null
        val guides = current.markupModel.allHighlighters.filter { it.isValid }
            .mapNotNull { (it.customRenderer as? BracketGuideDrawing)?.guide }
        check(guides.size <= 1) { "Multiple actual guide renderers were retained" }
        val guide = guides.singleOrNull() ?: return null
        val pair = guide.pair
        return UiObservedGuide(
            pair.openOffset, pair.openTokenLength, pair.closeOffset,
            pair.closeTokenLength, pair.depth, pair.openLine, pair.closeLine,
            guide.guideColumn, guide.anchorLine,
        )
    }

    private fun owned(highlighter: RangeHighlighter, current: Editor): Boolean {
        if (!highlighter.isValid) return false
        if (highlighter.customRenderer is BracketGuideDrawing ||
            highlighter.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true
        ) {
            return true
        }
        return highlighter.layer == HighlighterLayer.ELEMENT_UNDER_CARET &&
            highlighter.getTextAttributes(current.colorsScheme)?.effectType == EffectType.BOXED
    }

    override fun close() {
        assertEdt()
        val current = editor ?: return
        editor = null
        try {
            EditorGuides.dispose(current)
        } finally {
            try {
                settings.loadState(previousOptions)
            } finally {
                if (connected) {
                    connected = false
                    EditorGuides.disconnect()
                }
                advisory.dispose()
            }
        }
    }

    private fun assertEdt() = ApplicationManager.getApplication().assertIsDispatchThread()
}
