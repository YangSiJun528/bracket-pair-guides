package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.NewUI
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.events.NativeVisualEnvironment
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences

/** Supported native rendering paths for a standard monolithic editor. */
internal enum class NativeGuideUiPath {
    NEW_UI,
    CLASSIC_UI,
    UNCLASSIFIED,
}

/** Public indent-model facts needed to match a native carrier to plugin geometry. */
internal data class NativeIndentGuideGeometry(val indentLevel: Int, val startLine: Int, val endLine: Int) {
    fun matches(guide: BracketGuide): Boolean = indentLevel == guide.guideColumn &&
        startLine == guide.pair.openLine &&
        endLine == guide.pair.closeLine
}

/** Pure conflict inputs kept separate from IntelliJ renderer implementation details. */
internal data class NativeGuideConflictFacts(
    val pluginEnabledForEditor: Boolean,
    val activeGuideEnabled: Boolean,
    val verticalGuideEnabled: Boolean,
    val displayedMultilineVerticalGuide: BracketGuide?,
    val matchedBraceHighlightingEnabled: Boolean,
    val currentScopeHighlightingEnabled: Boolean,
    val directMatchedBraceResolvesPair: Boolean,
    val currentScopeResolvesPair: Boolean,
    val uiPath: NativeGuideUiPath,
    val effectiveIndentGuidesShown: Boolean,
    val lineMarkerAreaShown: Boolean,
    val matchingIndentGuide: NativeIndentGuideGeometry?,
    val supportedEditorPath: Boolean,
)

/** Classifies only native *highlight* conflicts, never ordinary dim indent guides. */
internal object NativeGuideConflictDetector {
    fun isConflict(facts: NativeGuideConflictFacts): Boolean {
        val guide = facts.displayedMultilineVerticalGuide ?: return false
        if (!facts.supportedEditorPath ||
            !facts.pluginEnabledForEditor ||
            !facts.activeGuideEnabled ||
            !facts.verticalGuideEnabled ||
            guide.pair.openLine >= guide.pair.closeLine ||
            !facts.matchedBraceHighlightingEnabled
        ) {
            return false
        }

        if (
            !hasVisibleNativeCarrier(
                uiPath = facts.uiPath,
                effectiveIndentGuidesShown = facts.effectiveIndentGuidesShown,
                lineMarkerAreaShown = facts.lineMarkerAreaShown,
                matchingIndentGuide = facts.matchingIndentGuide,
                guide = guide,
            )
        ) {
            return false
        }

        val directMatchedBraceCapability = facts.directMatchedBraceResolvesPair
        val currentScopeCapability =
            facts.currentScopeHighlightingEnabled &&
                facts.currentScopeResolvesPair
        if (!directMatchedBraceCapability && !currentScopeCapability) return false

        return true
    }

    fun isConflict(editor: Editor, guide: BracketGuide, preferences: BracketGuidePreferences): Boolean {
        val pair = guide.pair
        val supportedEditorPath = isSupportedEditor(editor)
        if (
            !supportedEditorPath ||
            !preferences.enabled ||
            !preferences.showActiveGuide ||
            !preferences.showVerticalGuide ||
            pair.openLine >= pair.closeLine
        ) {
            return false
        }

        val nativeSettings = CodeInsightSettings.getInstance()
        if (!nativeSettings.HIGHLIGHT_BRACES) return false
        val uiPath = currentUiPath()
        if (uiPath == NativeGuideUiPath.UNCLASSIFIED) return false
        val carrier = visibleNativeCarrier(editor, guide, uiPath)
        if (carrier == null) {
            // Brace attribution can enter a read action and scan highlighter
            // tokens. Do none of that when this UI path cannot paint a native
            // vertical carrier for the exact plugin guide geometry.
            return false
        }
        val markerSources =
            resolveMarkerSources(
                editor = editor,
                pair = pair,
                resolveCurrentScope = nativeSettings.HIGHLIGHT_SCOPE,
            )
        return isConflict(
            NativeGuideConflictFacts(
                // A currently installed plugin guide already proves that the
                // effective matcher/language capability was enabled.
                pluginEnabledForEditor = preferences.enabled,
                activeGuideEnabled = preferences.showActiveGuide,
                verticalGuideEnabled = preferences.showVerticalGuide,
                displayedMultilineVerticalGuide = guide,
                matchedBraceHighlightingEnabled = nativeSettings.HIGHLIGHT_BRACES,
                currentScopeHighlightingEnabled = nativeSettings.HIGHLIGHT_SCOPE,
                directMatchedBraceResolvesPair = markerSources.direct,
                currentScopeResolvesPair = markerSources.currentScope,
                uiPath = uiPath,
                effectiveIndentGuidesShown = carrier.effectiveIndentGuidesShown,
                lineMarkerAreaShown = carrier.lineMarkerAreaShown,
                matchingIndentGuide = carrier.matchingIndentGuide,
                supportedEditorPath = supportedEditorPath,
            ),
        )
    }

    /** Public-editor-state preflight used before any brace-source resolution. */
    internal fun hasVisibleCarrier(editor: Editor, guide: BracketGuide, uiPath: NativeGuideUiPath): Boolean =
        visibleNativeCarrier(editor, guide, uiPath) != null

    internal fun hasVisibleNativeCarrier(
        uiPath: NativeGuideUiPath,
        effectiveIndentGuidesShown: Boolean,
        lineMarkerAreaShown: Boolean,
        matchingIndentGuide: NativeIndentGuideGeometry?,
        guide: BracketGuide,
    ): Boolean = when (uiPath) {
        NativeGuideUiPath.NEW_UI ->
            effectiveIndentGuidesShown &&
                matchingIndentGuide?.matches(guide) == true

        NativeGuideUiPath.CLASSIC_UI -> lineMarkerAreaShown

        NativeGuideUiPath.UNCLASSIFIED -> false
    }

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
            !hasVisibleNativeCarrier(
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
        return NativeVisualEnvironment.isStandardMonolithicEditor(editor) &&
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
     * Resolves the actual marker source through the same public brace-matching
     * utilities used by IntelliJ. Merely enclosing the caret is insufficient:
     * another nested structural pair may own the current-scope marker.
     */
    internal fun resolveMarkerSources(
        editor: Editor,
        pair: BracketPair,
        resolveCurrentScope: Boolean,
    ): NativeMarkerSources {
        val project = editor.project ?: return NativeMarkerSources.NONE
        val document = editor.document
        if (!pair.hasWellFormedTokenRange(document.textLength)) return NativeMarkerSources.NONE
        if (
            editor.selectionModel.hasSelection() ||
            editor.softWrapModel.isInsideOrBeforeSoftWrap(editor.caretModel.visualPosition) ||
            TemplateManager.getInstance(project).getActiveTemplate(editor) != null
        ) {
            return NativeMarkerSources.NONE
        }

        return ApplicationManager.getApplication().runReadAction(
            Computable {
                val documentManager = PsiDocumentManager.getInstance(project)
                if (!documentManager.isCommitted(document)) return@Computable NativeMarkerSources.NONE
                val psiFile = documentManager.getPsiFile(document)
                    ?.takeIf(PsiFile::isValid)
                    ?: return@Computable NativeMarkerSources.NONE
                val highlighter = editor.highlighter
                val directContext =
                    BraceMatchingUtil.computeHighlightingAndNavigationContext(editor, psiFile)
                if (directContext != null) {
                    return@Computable NativeMarkerSources(
                        direct = directContext.resolves(pair, highlighter),
                        // IntelliJ does not add a current-scope line when the
                        // selected structural brace already owns highlighting.
                        currentScope = false,
                    )
                }

                if (
                    !resolveCurrentScope ||
                    caretHasAdjacentHorizontalWhitespace(document.charsSequence, editor)
                ) {
                    return@Computable NativeMarkerSources.NONE
                }
                NativeMarkerSources(
                    direct = false,
                    currentScope = currentScopeResolvesPair(editor, psiFile, highlighter, pair),
                )
            },
        )
    }

    /**
     * IntelliJ has a package-private retry for braces separated from the caret
     * by spaces or tabs. Public API cannot ask for its alternate offset, so
     * scope attribution fails closed when that retry could pre-empt the scope
     * path.
     */
    private fun caretHasAdjacentHorizontalWhitespace(chars: CharSequence, editor: Editor): Boolean {
        val offset = editor.caretModel.primaryCaret.offset
        if (offset < 0 || offset > chars.length) return true
        return (offset > 0 && chars[offset - 1].isHorizontalWhitespace()) ||
            (offset < chars.length && chars[offset].isHorizontalWhitespace())
    }

    private fun Char.isHorizontalWhitespace(): Boolean = this == ' ' || this == '\t'

    private fun currentScopeResolvesPair(
        editor: Editor,
        psiFile: PsiFile,
        highlighter: EditorHighlighter,
        pair: BracketPair,
    ): Boolean {
        val offset = editor.caretModel.primaryCaret.offset
        val document = editor.document
        if (offset < 0 || offset >= document.textLength || editor.foldingModel.isOffsetCollapsed(offset)) {
            return false
        }

        val chars = document.charsSequence
        val iterator = highlighter.createIterator(offset)
        if (iterator.atEnd()) return false
        val fileType = BraceMatchingUtil.getFileType(psiFile, offset)
        val onStructuralBrace =
            BraceMatchingUtil.isStructuralBraceToken(fileType, iterator, chars) &&
                (
                    BraceMatchingUtil.isRBraceToken(iterator, chars, fileType) ||
                        BraceMatchingUtil.isLBraceToken(iterator, chars, fileType)
                    )
        if (onStructuralBrace || !BraceMatchingUtil.findStructuralLeftBrace(fileType, iterator, chars)) {
            return false
        }

        val leftStart = iterator.start
        val leftEnd = iterator.end
        if (!BraceMatchingUtil.matchBrace(chars, fileType, iterator, true) || iterator.atEnd()) {
            return false
        }
        return leftStart == pair.openOffset &&
            leftEnd.toLong() == pair.openOffset.toLong() + pair.openTokenLength &&
            iterator.start == pair.closeOffset &&
            iterator.end.toLong() == pair.closeOffset.toLong() + pair.closeTokenLength
    }

    private fun BraceMatchingUtil.BraceHighlightingAndNavigationContext.resolves(
        pair: BracketPair,
        highlighter: EditorHighlighter,
    ): Boolean {
        val expectedNavigationOffsets =
            when (currentBraceOffset()) {
                pair.openOffset -> pair.closeOffset to pair.closeTokenLength
                pair.closeOffset -> pair.openOffset to pair.openTokenLength
                else -> return false
            }
        val (matchingStart, matchingLength) = expectedNavigationOffsets
        val matchingEnd = matchingStart.toLong() + matchingLength
        if (navigationOffset().toLong() !in setOf(matchingStart.toLong(), matchingEnd)) return false
        return highlighter.hasExactTokenRange(pair.openOffset, pair.openTokenLength) &&
            highlighter.hasExactTokenRange(pair.closeOffset, pair.closeTokenLength)
    }

    private fun EditorHighlighter.hasExactTokenRange(start: Int, length: Int): Boolean {
        val end = start.toLong() + length
        if (start < 0 || length <= 0 || end > Int.MAX_VALUE) return false
        val iterator: HighlighterIterator = createIterator(start)
        return !iterator.atEnd() && iterator.start == start && iterator.end.toLong() == end
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
