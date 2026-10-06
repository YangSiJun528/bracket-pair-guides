package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.NewUI
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.events.NativeVisualEnvironment
import com.sijunyang.bracketpairguides.editor.policy.NativeGuideConflictFacts
import com.sijunyang.bracketpairguides.editor.policy.NativeGuideConflictPolicy
import com.sijunyang.bracketpairguides.editor.policy.NativeGuideUiPath
import com.sijunyang.bracketpairguides.editor.policy.NativeIndentGuideGeometry
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences

/** Captures editor/UI facts and resolves native marker sources through platform read access. */
internal object NativeGuideConflictDetector {
    /** Captures only small editor/UI facts on EDT; no PSI or token traversal is performed here. */
    fun captureConflict(
        editor: Editor,
        guide: BracketGuide,
        preferences: BracketGuidePreferences,
    ): NativeConflictProbe? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val ui = captureUi(editor, guide, preferences) ?: return null
        val resolveSources = captureMarkerSources(editor, guide.pair, ui.facts.currentScopeHighlightingEnabled)
        return object : NativeConflictProbe {
            override suspend fun inspect(checkCanceled: () -> Unit): Boolean {
                val sources = resolveSources(checkCanceled)
                checkCanceled()
                return NativeGuideConflictPolicy.isConflict(
                    ui.facts.copy(
                        directMatchedBraceResolvesPair = sources.direct,
                        currentScopeResolvesPair = sources.currentScope,
                    ),
                )
            }

            override fun isCurrent(): Boolean = captureUi(editor, guide, preferences) == ui
        }
    }

    private fun captureUi(editor: Editor, guide: BracketGuide, preferences: BracketGuidePreferences): CapturedUi? {
        if (!preferences.enabled || !preferences.showActiveGuide || !preferences.showVerticalGuide ||
            guide.pair.openLine >= guide.pair.closeLine || !isSupportedEditor(editor)
        ) {
            return null
        }
        val project = checkNotNull(editor.project)
        val nativeSettings = CodeInsightSettings.getInstance()
        if (!nativeSettings.HIGHLIGHT_BRACES) return null
        val uiPath = currentUiPath()
        val carrier = visibleNativeCarrier(editor, guide, uiPath) ?: return null
        if (editor.selectionModel.hasSelection() ||
            editor.softWrapModel.isInsideOrBeforeSoftWrap(editor.caretModel.visualPosition) ||
            TemplateManager.getInstance(project).getActiveTemplate(editor) != null
        ) {
            return null
        }
        val offset = editor.caretModel.primaryCaret.offset
        return CapturedUi(
            facts = NativeGuideConflictFacts(
                pluginEnabledForEditor = true,
                activeGuideEnabled = true,
                verticalGuideEnabled = true,
                displayedMultilineVerticalGuide = guide,
                matchedBraceHighlightingEnabled = nativeSettings.HIGHLIGHT_BRACES,
                currentScopeHighlightingEnabled = nativeSettings.HIGHLIGHT_SCOPE,
                directMatchedBraceResolvesPair = false,
                currentScopeResolvesPair = false,
                uiPath = uiPath,
                effectiveIndentGuidesShown = carrier.effectiveIndentGuidesShown,
                lineMarkerAreaShown = carrier.lineMarkerAreaShown,
                matchingIndentGuide = carrier.matchingIndentGuide,
                supportedEditorPath = true,
            ),
            caretOffset = offset,
            blockCursor = editor.settings.isBlockCursor,
            caretCollapsed =
            offset in 0 until editor.document.textLength && editor.foldingModel.isOffsetCollapsed(offset),
        )
    }

    private data class CapturedUi(
        val facts: NativeGuideConflictFacts,
        val caretOffset: Int,
        val blockCursor: Boolean,
        val caretCollapsed: Boolean,
    )

    /** Public-editor-state preflight used before any brace-source resolution. */
    internal fun hasVisibleCarrier(editor: Editor, guide: BracketGuide, uiPath: NativeGuideUiPath): Boolean =
        visibleNativeCarrier(editor, guide, uiPath) != null

    private fun visibleNativeCarrier(editor: Editor, guide: BracketGuide, uiPath: NativeGuideUiPath): NativeCarrier? {
        if (uiPath == NativeGuideUiPath.UNCLASSIFIED) return null
        val effectiveIndentGuidesShown = editor.settings.isIndentGuidesShown
        val lineMarkerAreaShown = editor.settings.isLineMarkerAreaShown
        val matchingIndentGuide =
            if (uiPath == NativeGuideUiPath.NEW_UI && effectiveIndentGuidesShown) {
                matchingIndentGuide(editor, guide)
            } else {
                null
            }
        if (
            !NativeGuideConflictPolicy.hasVisibleNativeCarrier(
                uiPath = uiPath,
                effectiveIndentGuidesShown = effectiveIndentGuidesShown,
                lineMarkerAreaShown = lineMarkerAreaShown,
                matchingIndentGuide = matchingIndentGuide,
                guide = guide,
            )
        ) {
            return null
        }
        return NativeCarrier(
            effectiveIndentGuidesShown = effectiveIndentGuidesShown,
            lineMarkerAreaShown = lineMarkerAreaShown,
            matchingIndentGuide = matchingIndentGuide,
        )
    }

    private fun matchingIndentGuide(editor: Editor, guide: BracketGuide): NativeIndentGuideGeometry? {
        val pair = guide.pair
        val descriptor = editor.indentsModel.getDescriptor(pair.openLine, pair.closeLine)
        return descriptor?.toGeometry()?.takeIf { geometry -> geometry.matches(guide) }
    }

    internal fun currentUiPath(): NativeGuideUiPath = try {
        if (NewUI.isEnabled()) {
            NativeGuideUiPath.NEW_UI
        } else {
            NativeGuideUiPath.CLASSIC_UI
        }
    } catch (_: LinkageError) {
        NativeGuideUiPath.UNCLASSIFIED
    }

    private fun isSupportedEditor(editor: Editor): Boolean {
        val project = editor.project ?: return false
        return NativeVisualEnvironment.canManageNativeSettings() &&
            !project.isDisposed &&
            !editor.isDisposed &&
            !editor.isViewer &&
            !editor.isOneLineMode &&
            editor.contentComponent.isShowing &&
            editor.editorKind == EditorKind.MAIN_EDITOR &&
            FileEditorManager.getInstance(project).selectedTextEditor === editor &&
            FileDocumentManager.getInstance().getFile(editor.document) != null
    }

    private fun IndentGuideDescriptor.toGeometry(): NativeIndentGuideGeometry = NativeIndentGuideGeometry(
        indentLevel = indentLevel,
        startLine = startLine,
        endLine = endLine,
    )

    /**
     * Captures EDT inputs and returns background resolution through the same public brace-matching
     * utilities used by IntelliJ. Merely enclosing the caret is insufficient:
     * another nested structural pair may own the current-scope marker.
     */
    internal fun captureMarkerSources(
        editor: Editor,
        pair: BracketPair,
        resolveCurrentScope: Boolean,
    ): suspend (() -> Unit) -> NativeMarkerSources {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val project = editor.project ?: return { NativeMarkerSources.NONE }
        val document = editor.document
        if (!pair.hasWellFormedTokenRange(document.textLength)) return { NativeMarkerSources.NONE }
        if (
            editor.selectionModel.hasSelection() ||
            editor.softWrapModel.isInsideOrBeforeSoftWrap(editor.caretModel.visualPosition) ||
            TemplateManager.getInstance(project).getActiveTemplate(editor) != null
        ) {
            return { NativeMarkerSources.NONE }
        }

        val stamp = document.modificationStamp
        val highlighter = editor.highlighter
        val offset = editor.caretModel.primaryCaret.offset
        val blockCursor = editor.settings.isBlockCursor
        val collapsed = offset in 0 until document.textLength && editor.foldingModel.isOffsetCollapsed(offset)
        val epoch = com.intellij.openapi.components.service<com.sijunyang.bracketpairguides.analysis.intellij.AnalysisReadEpoch>()
        val inspection = NativeMarkerInspection(editor, pair, resolveCurrentScope, offset, blockCursor, collapsed,
            highlighter, stamp, epoch, epoch.current)
        return { checkCanceled -> inspection.resolve(checkCanceled) }
    }

    internal data class NativeMarkerSources(val direct: Boolean, val currentScope: Boolean) {
        companion object {
            val NONE = NativeMarkerSources(direct = false, currentScope = false)
        }
    }

    private data class NativeCarrier(
        val effectiveIndentGuidesShown: Boolean,
        val lineMarkerAreaShown: Boolean,
        val matchingIndentGuide: NativeIndentGuideGeometry?,
    )
}
