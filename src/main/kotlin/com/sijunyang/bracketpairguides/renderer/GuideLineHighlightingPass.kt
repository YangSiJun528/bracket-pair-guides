package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.DocumentUtil
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
    private var collectedPositionIndex: GuidePositionIndex? = null
    private var collectedActiveIndex: ActiveBracketPairIndex? = null

    override fun doCollectInformation(progress: ProgressIndicator) {
        val stamp = currentStamp()
        if (!stamp.analysisEnabled) {
            collectedPairs = emptyList()
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
            collectedPositionIndex = null
            collectedActiveIndex = ActiveBracketPairIndex.build(emptyList())
            collectedStamp = stamp
            return
        }

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
        val activeIndex = collectedActiveIndex ?: return
        val stamp = collectedStamp ?: return
        if (editor.isDisposed || stamp != currentStamp()) return

        val previousState = editor.getUserData(APPLIED_STATE_KEY)
        val previousDecorationCount =
            previousState?.entries?.size?.toLong().orZero() +
                (if (previousState?.activeGuide != null) 1L else 0L) +
                previousState?.activePairHighlights?.size?.toLong().orZero()
        val nextDecorationCount =
            pairs.size.toLong() * CACHED_DECORATIONS_PER_PAIR +
                (if (pairs.isEmpty()) 0L else MAX_ACTIVE_DECORATIONS)
        DocumentUtil.executeInBulk(
            editor.document,
            previousDecorationCount + nextDecorationCount > BULK_DECORATION_THRESHOLD,
        ) {
            applyPairs(
                pairs = pairs,
                activeIndex = activeIndex,
                positionIndex = collectedPositionIndex,
            )
        }
    }

    private fun applyPairs(
        pairs: List<BracketPair>,
        activeIndex: ActiveBracketPairIndex,
        positionIndex: GuidePositionIndex?,
    ) {
        val settings = PluginSettings.getInstance().state
        val previous = editor.getUserData(APPLIED_STATE_KEY)
        previous?.activeGuide?.dispose()
        previous?.activePairHighlights.orEmpty().forEach(RangeHighlighter::dispose)
        val reusable = HashMap<RangeKey, ArrayDeque<RangeHighlighter>>()
        previous?.entries.orEmpty().forEach { entry ->
            val highlighter = entry.highlighter
            if (highlighter.isValid) {
                reusable.getOrPut(
                    RangeKey(highlighter.startOffset, highlighter.endOffset),
                ) { ArrayDeque() }.addLast(highlighter)
            } else {
                highlighter.dispose()
            }
        }

        val applied = ArrayList<AppliedEntry>(pairs.size * 2)
        val guides = ArrayList<BracketGuide?>(pairs.size)
        pairs.forEach { pair ->
            guides += createGuideDescriptor(editor, pair, positionIndex)

            val colorKey = BracketColorPalette.LEVEL_KEYS[
                BracketColorPalette.levelIndex(pair.depth)
            ]
            applied += applyBracketColor(
                reusable = reusable,
                startOffset = pair.openOffset,
                endOffset = pair.openOffset + pair.openTokenLength,
                colorKey = colorKey,
                depth = pair.depth,
                enabled = settings.enabled && settings.colorBracketTokens,
            )
            applied += applyBracketColor(
                reusable = reusable,
                startOffset = pair.closeOffset,
                endOffset = pair.closeOffset + pair.closeTokenLength,
                colorKey = colorKey,
                depth = pair.depth,
                enabled = settings.enabled && settings.colorBracketTokens,
            )
        }

        reusable.values.forEach { queue ->
            queue.forEach(RangeHighlighter::dispose)
        }
        val activePairIndex = activeIndex.activePairIndex(
            editor.caretModel.primaryCaret.offset,
        )
        val activeDescriptor = guides.getOrNull(activePairIndex)
        editor.putUserData(
            APPLIED_STATE_KEY,
            AppliedState(
                stamp = checkNotNull(collectedStamp),
                activeIndex = activeIndex,
                guides = guides,
                activePairIndex = if (activeDescriptor == null) {
                    ActiveBracketPairIndex.NO_PAIR
                } else {
                    activePairIndex
                },
                activeGuide = activeDescriptor?.let {
                    ActivePairDecoration.addGuide(editor, it, settings)
                },
                activePairHighlights = activeDescriptor?.let {
                    ActivePairDecoration.addPairHighlights(editor, it, settings)
                }.orEmpty(),
                entries = applied,
            ),
        )
    }

    private fun applyBracketColor(
        reusable: Map<RangeKey, ArrayDeque<RangeHighlighter>>,
        startOffset: Int,
        endOffset: Int,
        colorKey: TextAttributesKey,
        depth: Int,
        enabled: Boolean,
    ): AppliedEntry {
        val rangeKey = RangeKey(startOffset, endOffset)
        val highlighter = reusable[rangeKey]?.removeFirstOrNull()
            ?: editor.markupModel.addRangeHighlighter(
                colorKey,
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                HighlighterTargetArea.EXACT_RANGE,
            )

        applyBracketPresentation(highlighter, colorKey, depth, enabled)
        highlighter.customRenderer = null
        highlighter.putUserData(GUIDE_KEY, null)
        highlighter.putUserData(OWNED_HIGHLIGHTER_KEY, true)
        return AppliedEntry(highlighter, colorKey, depth)
    }

    private fun applyBracketPresentation(
        highlighter: RangeHighlighter,
        colorKey: TextAttributesKey,
        depth: Int,
        enabled: Boolean,
    ) {
        highlighter.setTextAttributesKey(if (enabled) colorKey else DISABLED_ATTRIBUTES_KEY)
        (highlighter as? RangeHighlighterEx)?.textAttributes = if (enabled) {
            BracketColorPalette.bracketTextAttributes(
                editor.colorsScheme,
                PluginSettings.getInstance().state,
                depth,
            )
        } else {
            null
        }
    }

    private fun currentStamp(): AnalysisStamp {
        return currentStamp(editor)
    }

    private fun currentTabSize(): Int {
        return editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    }

    private data class RangeKey(
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class AppliedEntry(
        val highlighter: RangeHighlighter,
        val colorKey: TextAttributesKey? = null,
        val depth: Int = 0,
    )

    private data class AppliedState(
        val stamp: AnalysisStamp,
        val activeIndex: ActiveBracketPairIndex,
        val guides: List<BracketGuide?>,
        val activePairIndex: Int,
        val activeGuide: RangeHighlighter?,
        val activePairHighlights: List<RangeHighlighter>,
        val entries: List<AppliedEntry>,
    )

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

        private val DISABLED_ATTRIBUTES_KEY: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "BRACKET_PAIR_GUIDES_DISABLED_ATTRIBUTES",
            )

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
            val nextPairIndex = if (state.stamp == currentStamp(editor)) {
                state.activeIndex.activePairIndex(editor.caretModel.primaryCaret.offset)
            } else {
                ActiveBracketPairIndex.NO_PAIR
            }
            if (state.activePairIndex == nextPairIndex) return

            state.activeGuide?.dispose()
            state.activePairHighlights.forEach(RangeHighlighter::dispose)
            val settings = PluginSettings.getInstance().state
            val nextGuide = state.guides.getOrNull(nextPairIndex)
            val effectivePairIndex = if (nextGuide == null) {
                ActiveBracketPairIndex.NO_PAIR
            } else {
                nextPairIndex
            }
            editor.putUserData(
                APPLIED_STATE_KEY,
                state.copy(
                    activePairIndex = effectivePairIndex,
                    activeGuide = nextGuide?.let {
                        ActivePairDecoration.addGuide(editor, it, settings)
                    },
                    activePairHighlights = nextGuide?.let {
                        ActivePairDecoration.addPairHighlights(editor, it, settings)
                    }.orEmpty(),
                ),
            )
            editor.contentComponent.repaint()
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
            state.entries.forEach { entry ->
                if (!entry.highlighter.isValid) return@forEach
                entry.highlighter.setTextAttributesKey(
                    if (settings.enabled && settings.colorBracketTokens) {
                        checkNotNull(entry.colorKey)
                    } else {
                        DISABLED_ATTRIBUTES_KEY
                    },
                )
                (entry.highlighter as? RangeHighlighterEx)?.textAttributes =
                    if (settings.enabled && settings.colorBracketTokens) {
                        BracketColorPalette.bracketTextAttributes(
                            editor.colorsScheme,
                            settings,
                            entry.depth,
                        )
                    } else {
                        null
                    }
            }

            state.activeGuide?.dispose()
            state.activePairHighlights.forEach(RangeHighlighter::dispose)
            val activePairIndex = if (state.stamp == currentStamp(editor)) {
                state.activeIndex.activePairIndex(editor.caretModel.primaryCaret.offset)
            } else {
                ActiveBracketPairIndex.NO_PAIR
            }
            val activeDescriptor = state.guides.getOrNull(activePairIndex)
            editor.putUserData(
                APPLIED_STATE_KEY,
                state.copy(
                    activePairIndex = if (activeDescriptor == null) {
                        ActiveBracketPairIndex.NO_PAIR
                    } else {
                        activePairIndex
                    },
                    activeGuide = activeDescriptor?.let {
                        ActivePairDecoration.addGuide(editor, it, settings)
                    },
                    activePairHighlights = activeDescriptor?.let {
                        ActivePairDecoration.addPairHighlights(editor, it, settings)
                    }.orEmpty(),
                ),
            )
            editor.contentComponent.repaint()
        }

        internal fun clearEditorState(editor: Editor) {
            val state = editor.getUserData(APPLIED_STATE_KEY) ?: return
            editor.putUserData(APPLIED_STATE_KEY, null)
            state.activeGuide?.dispose()
            state.activePairHighlights.forEach(RangeHighlighter::dispose)
            state.entries.forEach { entry ->
                if (entry.highlighter.isValid) entry.highlighter.dispose()
            }
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

        private const val CACHED_DECORATIONS_PER_PAIR = 2L
        private const val MAX_ACTIVE_DECORATIONS = 3L
        private const val BULK_DECORATION_THRESHOLD = 10_000L

        private fun Long?.orZero(): Long = this ?: 0L
    }
}
