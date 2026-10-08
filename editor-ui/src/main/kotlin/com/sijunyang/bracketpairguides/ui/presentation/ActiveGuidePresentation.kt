package com.sijunyang.bracketpairguides.ui.presentation

import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.presentation.RenderFrames.Frame

/** Tracked active-pair state and its editor markup for one editor session. */
internal class ActiveGuidePresentation(
    private val editor: Editor,
    onDisplayedMultilineVerticalGuide: (Editor, BracketGuide) -> Unit = { _, _ -> },
) {
    private val trackedPair = TrackedBracketPair(editor)
    private var pendingAnchorLine: Int? = null
    private val markup = ActivePairMarkup(editor, onDisplayedMultilineVerticalGuide)

    val currentPair: BracketPair?
        get() = trackedPair.current

    val adjustedPair: BracketPair?
        get() = trackedPair.adjusted

    val needsGuideRepair: Boolean
        get() = currentPair?.let { it.openLine != it.closeLine } == true && currentGuide() == null

    val guideAnchorLine: Int?
        get() = trackedPair.anchorLine ?: pendingAnchorLine

    fun isDisplayed(guide: BracketGuide): Boolean = currentGuide() == guide

    /** A live accepted view can keep already established geometry; pending/provisional work cannot. */
    fun adoptUnchanged(pair: BracketPair?, preferences: BracketGuidePreferences, frame: Frame): Boolean {
        frame.check()
        if (pair == null || !pair.hasWellFormedTokenRange(editor.document.textLength)) return false
        val guide = currentGuide() ?: return false
        if (guide.pair != pair || !trackedPair.matches(pair, guide)) return false
        frame.check()
        return markup.adoptUnchanged(pair, preferences, frame)
    }

    fun replace(
        pair: BracketPair?,
        indexedGuide: BracketGuide?,
        allowGuideFallback: Boolean,
        preferences: BracketGuidePreferences,
        frame: Frame,
    ) {
        frame.check()
        val previousGuide = currentGuide()
        val currentAnchorLine = guideAnchorLine
        // Keep compatible tracking and SDK effects until their replacement can adopt them.
        if (pair == null || !preferences.enabled ||
            (!preferences.showsGuide && !preferences.showsActivePair) ||
            !pair.hasWellFormedTokenRange(editor.document.textLength)
        ) {
            clear(preserveGuide = false, frame = frame)
            return
        }

        val guide =
            createGuide(
                pair = pair,
                indexedGuide = indexedGuide,
                previousGuide = previousGuide,
                currentAnchorLine = currentAnchorLine,
                allowGuideFallback = allowGuideFallback,
                preferences = preferences,
            )
        trackedPair.track(pair, guide)
        pendingAnchorLine = if (guide == null) currentAnchorLine else null
        markup.showGuide(guide, preferences, frame)
        frame.check()
        markup.showPair(pair, preferences, frame)
        frame.check()
    }

    fun refreshProvisional(caretOffset: Int, preferences: BracketGuidePreferences, frame: Frame) {
        frame.check()
        val pair = trackedPair.adjusted
        if (pair?.contains(caretOffset) != true) {
            clear(preserveGuide = false, frame = frame)
            return
        }

        val previousGuide = currentGuide()
        val guide =
            when {
                !preferences.enabled || !preferences.showsGuide -> {
                    null
                }

                pair.openLine == pair.closeLine -> {
                    BracketGuide(pair, guideColumn = 0)
                }

                previousGuide == null -> {
                    null
                }

                else -> {
                    previousGuide.copy(
                        pair = pair,
                        anchorLine =
                        (trackedPair.anchorLine ?: previousGuide.anchorLine)
                            .coerceIn(pair.openLine, pair.closeLine),
                    )
                }
            }
        markup.showGuide(guide, preferences, frame)
        frame.check()
        trackedPair.refresh(pair, guide)
    }

    /** An affected guide is hidden before the document callback returns. */
    fun refreshAfterDocumentChange(
        change: DocumentChange,
        caretOffset: Int,
        preferences: BracketGuidePreferences,
        frame: Frame,
    ) {
        frame.check()
        val previousPair = trackedPair.current
        if (previousPair == null || change.altersToken(previousPair)) {
            clear(preserveGuide = false, frame = frame)
            return
        }

        val pair = trackedPair.adjusted
        if (pair?.contains(caretOffset) != true ||
            !pair.hasWellFormedTokenRange(editor.document.textLength)
        ) {
            clear(preserveGuide = false, frame = frame)
            return
        }

        val previousGuide = currentGuide()
        val guide =
            when {
                !preferences.enabled || !preferences.showsGuide -> {
                    null
                }

                pair.openLine == pair.closeLine -> {
                    BracketGuide(pair, guideColumn = 0)
                }

                else -> {
                    GuidePositionFallback.guideAfterChange(
                        editor = editor,
                        pair = pair,
                        previousPair = previousPair,
                        previous = previousGuide,
                        currentAnchorLine = trackedPair.anchorLine,
                        change = change,
                    )
                }
            }
        // Missing geometry hides guide pixels immediately; adjusted pair tokens remain visible.
        markup.showGuide(guide, preferences, frame)
        frame.check()
        markup.showPair(pair, preferences, frame)
        frame.check()
        trackedPair.refresh(pair, guide)
    }

    fun hideGuide(frame: Frame) {
        frame.check()
        pendingAnchorLine = guideAnchorLine
        currentPair?.let { trackedPair.refresh(it, null) }
        markup.clearGuide(frame)
    }

    fun publishRepair(guide: BracketGuide, preferences: BracketGuidePreferences, frame: Frame): Boolean {
        frame.check()
        if (currentPair != guide.pair || adjustedPair != guide.pair || !needsGuideRepair) return false
        trackedPair.refresh(guide.pair, guide)
        markup.showGuide(guide, preferences, frame)
        frame.check()
        return true
    }

    fun clear(preserveGuide: Boolean, frame: Frame? = null) {
        frame?.check()
        trackedPair.clear()
        pendingAnchorLine = null
        markup.clear(preserveGuide, frame)
    }

    private fun createGuide(
        pair: BracketPair,
        indexedGuide: BracketGuide?,
        previousGuide: BracketGuide?,
        currentAnchorLine: Int?,
        allowGuideFallback: Boolean,
        preferences: BracketGuidePreferences,
    ): BracketGuide? {
        if (!preferences.enabled || !preferences.showsGuide) return null
        if (pair.openLine == pair.closeLine) return BracketGuide(pair, 0)
        // A tracked pair can outlive its snapshot during edits and settings
        // transitions. Keep that provisional presentation bounded until the
        // background pass publishes an exact guide index.
        return indexedGuide ?: if (allowGuideFallback) {
            GuidePositionFallback.guideFor(
                editor,
                pair,
                previousGuide,
                currentAnchorLine,
            )
        } else {
            null
        }
    }

    private fun currentGuide(): BracketGuide? = markup.guide

    private fun BracketPair.contains(offset: Int): Boolean =
        offset > openOffset && offset.toLong() < closeOffset.toLong() + closeTokenLength
}
