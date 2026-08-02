package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import java.awt.Color

/**
 * Follows the same lifecycle as the platform's built-in indent-guide pass:
 * collect immutable descriptors under a background read action, then diff and
 * apply range highlighters on the EDT.
 */
class GuideLineHighlightingPass internal constructor(
    project: Project,
    private val editor: Editor,
    private val pairProvider: BracketPairProvider,
    private val visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
) : TextEditorHighlightingPass(project, editor.document, false) {
    init {
        CaretGuideController.ensureInitialized()
    }

    constructor(project: Project, editor: Editor) : this(
        project = project,
        editor = editor,
        pairProvider = BracketPairAnalyzer(editor),
    )

    constructor(project: Project, editor: Editor, fileType: FileType) : this(
        project = project,
        editor = editor,
        pairProvider = BracketPairAnalyzer(editor, fileType),
    )

    private var collectedStamp: AnalysisStamp? = null
    private var collectedPairs: List<BracketPair>? = null
    private var collectedTokenIndex: BracketTokenIndex? = null
    private var collectedPositionIndex: GuidePositionIndex? = null
    private var collectedActiveIndex: ActiveBracketPairIndex? = null

    override fun doCollectInformation(progress: ProgressIndicator) {
        val stamp = currentStamp()
        if (!stamp.analysisEnabled) {
            collectedPairs = emptyList()
            collectedTokenIndex = BracketTokenIndex.build(emptyList())
            collectedPositionIndex = null
            collectedActiveIndex = ActiveBracketPairIndex.build(emptyList())
            collectedStamp = stamp
            return
        }
        val appliedState = editor.getUserData(APPLIED_STATE_KEY)
        if (appliedState?.stamp == stamp) {
            return
        }

        val pairs = pairProvider.collect(progress)
        if (pairs.isEmpty()) {
            collectedPairs = emptyList()
            collectedTokenIndex = BracketTokenIndex.build(emptyList())
            collectedPositionIndex = null
            collectedActiveIndex = ActiveBracketPairIndex.build(emptyList())
            collectedStamp = stamp
            return
        }

        collectedTokenIndex = BracketTokenIndex.build(pairs, progress::checkCanceled)
        collectedPositionIndex = if (pairs.any { it.openLine != it.closeLine }) {
            GuidePositionIndex.from(
                document = editor.document,
                tabSize = currentTabSize(),
                progress = progress,
            )
        } else {
            null
        }
        collectedPairs = pairs
        collectedActiveIndex = ActiveBracketPairIndex.build(pairs, progress::checkCanceled)
        collectedStamp = stamp
    }

    override fun doApplyInformationToEditor() {
        val pairs = collectedPairs ?: return
        val tokenIndex = collectedTokenIndex ?: return
        val activeIndex = collectedActiveIndex ?: return
        val stamp = collectedStamp ?: return
        if (editor.isDisposed || stamp != currentStamp()) return

        val previousState = editor.getUserData(APPLIED_STATE_KEY)
        applyPairs(
            pairs = pairs,
            tokenIndex = tokenIndex,
            activeIndex = activeIndex,
            positionIndex = collectedPositionIndex,
            previousState = previousState,
        )
    }

    private fun applyPairs(
        pairs: List<BracketPair>,
        tokenIndex: BracketTokenIndex,
        activeIndex: ActiveBracketPairIndex,
        positionIndex: GuidePositionIndex?,
        previousState: AppliedState?,
    ) {
        val settings = PluginSettings.getInstance().state
        disposeActivePresentation(previousState, preserveGuide = true)
        val tokenDecorations = VisibleTokenDecorationManager.replace(
            editor,
            previousState?.tokenDecorations,
            tokenIndex,
            visibleRangeProvider(editor),
            settings,
        )
        val activePairIndex = activeIndex.activePairIndex(
            editor.caretModel.primaryCaret.offset,
        )
        val activePresentation = createActivePresentation(
            editor = editor,
            pairs = pairs,
            pairIndex = activePairIndex,
            positionIndex = positionIndex,
            settings = settings,
            reusableGuide = previousState?.activeGuide,
        )
        editor.putUserData(
            APPLIED_STATE_KEY,
            AppliedState(
                stamp = checkNotNull(collectedStamp),
                pairs = pairs,
                tokenIndex = tokenIndex,
                activeIndex = activeIndex,
                positionIndex = positionIndex,
                visibleRangeProvider = visibleRangeProvider,
                tokenDecorations = tokenDecorations,
                activePairIndex = activePresentation.pairIndex,
                activeRange = activePresentation.range,
                activeGuide = activePresentation.guide,
                activePairHighlights = activePresentation.pairHighlights,
            ),
        )
    }

    private fun currentStamp(): AnalysisStamp {
        return currentStamp(editor)
    }

    private fun currentTabSize(): Int {
        return editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    }

    private data class AppliedState(
        val stamp: AnalysisStamp,
        val pairs: List<BracketPair>,
        val tokenIndex: BracketTokenIndex,
        val activeIndex: ActiveBracketPairIndex,
        val positionIndex: GuidePositionIndex?,
        val visibleRangeProvider: (Editor) -> TextRange,
        val tokenDecorations: VisibleTokenDecorations,
        val activePairIndex: Int,
        val activeRange: RangeMarker?,
        val activeGuide: RangeHighlighter?,
        val activePairHighlights: List<RangeHighlighter>,
    )

    private data class ActivePresentation(
        val pairIndex: Int,
        val range: RangeMarker?,
        val guide: RangeHighlighter?,
        val pairHighlights: List<RangeHighlighter>,
    ) {
        companion object {
            val EMPTY = ActivePresentation(
                ActiveBracketPairIndex.NO_PAIR,
                null,
                null,
                emptyList(),
            )
        }
    }

    private data class AnalysisStamp(
        val documentStamp: Long,
        val tabSize: Int,
        val highlighterIdentity: Int,
        val analysisEnabled: Boolean,
    )

    companion object {
        internal val GUIDE_KEY: Key<BracketGuide> =
            Key.create("bracket.pair.guides.descriptor")

        internal val OWNED_HIGHLIGHTER_KEY: Key<Boolean> =
            Key.create("bracket.pair.guides.owned.highlighter")

        internal val ACTIVE_PAIR_HIGHLIGHT_KEY: Key<Boolean> =
            Key.create("bracket.pair.guides.active.pair.highlight")

        internal val GUIDE_RENDER_OPTIONS_KEY: Key<GuideRenderOptions> =
            Key.create("bracket.pair.guides.render.options")

        internal val GUIDE_COLOR_KEY: Key<Color> =
            Key.create("bracket.pair.guides.render.color")

        private val APPLIED_STATE_KEY: Key<AppliedState> =
            Key.create("bracket.pair.guides.applied.state")

        internal fun updateActivePresentation(editor: Editor) {
            val application = ApplicationManager.getApplication()
            if (!application.isDispatchThread) {
                application.invokeLater {
                    if (!editor.isDisposed) updateActivePresentation(editor)
                }
                return
            }
            if (editor.isDisposed) return

            val state = editor.getUserData(APPLIED_STATE_KEY) ?: return
            if (state.stamp != currentStamp(editor)) {
                updateOptimisticActivePresentation(editor, state)
                return
            }
            val nextPairIndex = state.activeIndex.activePairIndex(
                editor.caretModel.primaryCaret.offset,
            )
            if (state.activePairIndex == nextPairIndex) return

            disposeActivePresentation(state, preserveGuide = true)
            val settings = PluginSettings.getInstance().state
            val activePresentation = createActivePresentation(
                editor,
                state.pairs,
                nextPairIndex,
                state.positionIndex,
                settings,
                state.activeGuide,
            )
            editor.putUserData(
                APPLIED_STATE_KEY,
                state.copy(
                    activePairIndex = activePresentation.pairIndex,
                    activeRange = activePresentation.range,
                    activeGuide = activePresentation.guide,
                    activePairHighlights = activePresentation.pairHighlights,
                ),
            )
            editor.contentComponent.repaint()
        }

        internal fun updateVisiblePresentation(editor: Editor) {
            val application = ApplicationManager.getApplication()
            if (!application.isDispatchThread) {
                application.invokeLater {
                    if (!editor.isDisposed) updateVisiblePresentation(editor)
                }
                return
            }
            if (editor.isDisposed) return

            val state = editor.getUserData(APPLIED_STATE_KEY) ?: return
            if (state.stamp != currentStamp(editor)) return
            val tokenDecorations = VisibleTokenDecorationManager.replaceIfOutsideWindow(
                editor,
                state.tokenDecorations,
                state.tokenIndex,
                state.visibleRangeProvider(editor),
                PluginSettings.getInstance().state,
            ) ?: return
            editor.putUserData(
                APPLIED_STATE_KEY,
                state.copy(tokenDecorations = tokenDecorations),
            )
            editor.contentComponent.repaint()
        }

        internal fun updateAfterDocumentChange(editor: Editor) {
            val application = ApplicationManager.getApplication()
            if (!application.isDispatchThread) {
                application.invokeLater {
                    if (!editor.isDisposed) updateAfterDocumentChange(editor)
                }
                return
            }
            if (editor.isDisposed) return

            val state = editor.getUserData(APPLIED_STATE_KEY) ?: return
            if (state.stamp == currentStamp(editor)) return
            updateOptimisticActivePresentation(editor, state)
        }

        internal fun refreshSettings(editor: Editor) {
            val application = ApplicationManager.getApplication()
            if (!application.isDispatchThread) {
                application.invokeLater {
                    if (!editor.isDisposed) refreshSettings(editor)
                }
                return
            }
            if (editor.isDisposed) return

            val state = editor.getUserData(APPLIED_STATE_KEY) ?: return
            val settings = PluginSettings.getInstance().state
            val current = state.stamp == currentStamp(editor)
            val tokenDecorations = if (current) {
                VisibleTokenDecorationManager.replace(
                    editor,
                    state.tokenDecorations,
                    state.tokenIndex,
                    state.visibleRangeProvider(editor),
                    settings,
                )
            } else {
                VisibleTokenDecorationManager.updateAttributes(
                    editor,
                    state.tokenDecorations,
                    settings,
                )
            }

            val activePairIndex = if (current) {
                state.activeIndex.activePairIndex(editor.caretModel.primaryCaret.offset)
            } else {
                state.activePairIndex
            }
            val activePair = if (current) {
                state.pairs.getOrNull(activePairIndex)
            } else {
                adjustedActivePair(editor, state)
            }
            disposeActivePresentation(state, preserveGuide = true)
            val activePresentation = activePair?.let { pair ->
                createActivePresentation(
                    editor,
                    pair,
                    activePairIndex,
                    state.positionIndex,
                    settings,
                    state.activeGuide,
                )
            } ?: ActivePresentation.EMPTY.also { state.activeGuide?.dispose() }
            editor.putUserData(
                APPLIED_STATE_KEY,
                state.copy(
                    tokenDecorations = tokenDecorations,
                    activePairIndex = activePresentation.pairIndex,
                    activeRange = activePresentation.range,
                    activeGuide = activePresentation.guide,
                    activePairHighlights = activePresentation.pairHighlights,
                ),
            )
            editor.contentComponent.repaint()
        }

        internal fun clearEditorState(editor: Editor) {
            val state = editor.getUserData(APPLIED_STATE_KEY) ?: return
            editor.putUserData(APPLIED_STATE_KEY, null)
            disposeActivePresentation(state)
            VisibleTokenDecorationManager.dispose(state.tokenDecorations)
        }

        private fun createActivePresentation(
            editor: Editor,
            pairs: List<BracketPair>,
            pairIndex: Int,
            positionIndex: GuidePositionIndex?,
            settings: PluginSettings.State,
            reusableGuide: RangeHighlighter? = null,
        ): ActivePresentation {
            val pair = pairs.getOrNull(pairIndex)
                ?: return ActivePresentation.EMPTY.also { reusableGuide?.dispose() }
            return createActivePresentation(
                editor,
                pair,
                pairIndex,
                positionIndex,
                settings,
                reusableGuide,
            )
        }

        private fun createActivePresentation(
            editor: Editor,
            pair: BracketPair,
            pairIndex: Int,
            positionIndex: GuidePositionIndex?,
            settings: PluginSettings.State,
            reusableGuide: RangeHighlighter? = null,
        ): ActivePresentation {
            val guide = createGuideDescriptor(editor, pair, positionIndex)
                ?: return ActivePresentation.EMPTY.also { reusableGuide?.dispose() }
            val range = editor.document.createRangeMarker(
                pair.openOffset,
                pair.closeOffset + pair.closeTokenLength,
            ).apply {
                isGreedyToLeft = false
                isGreedyToRight = false
            }
            return ActivePresentation(
                pairIndex = pairIndex,
                range = range,
                guide = ActivePairDecoration.addGuide(editor, guide, settings, reusableGuide),
                pairHighlights = ActivePairDecoration.addPairHighlights(editor, guide, settings),
            )
        }

        private fun disposeActivePresentation(
            state: AppliedState?,
            preserveGuide: Boolean = false,
        ) {
            state ?: return
            state.activeRange?.dispose()
            if (!preserveGuide) state.activeGuide?.dispose()
            for (highlighter in state.activePairHighlights) highlighter.dispose()
        }

        private fun updateOptimisticActivePresentation(editor: Editor, state: AppliedState) {
            val pair = adjustedActivePair(editor, state)
            val caretOffset = editor.caretModel.primaryCaret.offset
            if (pair == null || caretOffset <= pair.openOffset ||
                caretOffset >= pair.closeOffset + pair.closeTokenLength
            ) {
                disposeActivePresentation(state)
                editor.putUserData(
                    APPLIED_STATE_KEY,
                    state.copy(
                        activePairIndex = ActiveBracketPairIndex.NO_PAIR,
                        activeRange = null,
                        activeGuide = null,
                        activePairHighlights = emptyList(),
                    ),
                )
                editor.contentComponent.repaint()
                return
            }

            val oldGuide = state.activeGuide?.getUserData(GUIDE_KEY)
            if (oldGuide != null) {
                state.activeGuide.putUserData(
                    GUIDE_KEY,
                    BracketGuide(pair, oldGuide.guideColumn),
                )
            }
            editor.contentComponent.repaint()
        }

        private fun adjustedActivePair(editor: Editor, state: AppliedState): BracketPair? {
            val original = state.pairs.getOrNull(state.activePairIndex) ?: return null
            val range = state.activeRange?.takeIf(RangeMarker::isValid) ?: return null
            val closeOffset = range.endOffset - original.closeTokenLength
            if (range.startOffset + original.openTokenLength > closeOffset) return null
            return original.copy(
                openOffset = range.startOffset,
                closeOffset = closeOffset,
                openLine = editor.document.getLineNumber(range.startOffset),
                closeLine = editor.document.getLineNumber(closeOffset),
            )
        }

        private fun createGuideDescriptor(
            editor: Editor,
            pair: BracketPair,
            positionIndex: GuidePositionIndex?,
        ): BracketGuide? {
            val endOffset = pair.closeOffset.toLong() + pair.closeTokenLength
            if (pair.openOffset < 0 ||
                pair.openOffset.toLong() >= endOffset ||
                endOffset > editor.document.textLength
            ) {
                return null
            }
            return guideFor(pair, positionIndex)
        }

        private fun guideFor(
            pair: BracketPair,
            positionIndex: GuidePositionIndex?,
        ): BracketGuide {
            return if (pair.openLine == pair.closeLine) {
                BracketGuide(pair, guideColumn = 0)
            } else {
                checkNotNull(positionIndex).guideFor(pair)
            }
        }

        private fun currentStamp(editor: Editor): AnalysisStamp {
            return AnalysisStamp(
                documentStamp = editor.document.modificationStamp,
                tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1),
                highlighterIdentity = System.identityHashCode(editor.highlighter),
                analysisEnabled = PluginSettings.getInstance().state.enabled,
            )
        }

    }
}
