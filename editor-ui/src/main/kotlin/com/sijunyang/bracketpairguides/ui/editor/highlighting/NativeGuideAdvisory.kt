package com.sijunyang.bracketpairguides.ui.editor.highlighting

import com.intellij.application.options.editor.EditorOptionsListener
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.settings.NativeGuideConflictSettingsListener
import com.sijunyang.bracketpairguides.ui.settings.ui.BracketGuideSettingsPage
import com.sijunyang.bracketpairguides.ui.work.NativeConflictEvidence
import com.sijunyang.bracketpairguides.ui.work.NativeInterest

/** Owns advisory episodes and notification effects, never native proof work. */
@State(
    name = "BracketPairGuidesNativeGuideConflictNotification",
    storages = [Storage("bracket-pair-guides-native-guide-notification.xml")],
)
class NativeGuideAdvisory :
    SerializablePersistentStateComponent<NativeGuideAdvisory.Suppression>(Suppression()),
    NativeGuideConflictSettingsListener,
    EditorOptionsListener,
    Disposable {
    private var episode = 0L
    private var capable: Boolean? = null
    private var shown = false
    private var closed = false
    private var lastOptions = BracketGuidePreferences()
    private var notification: Notification? = null

    data class Suppression(@JvmField @field:Property val suppressedForCurrentConflict: Boolean = false)

    fun interest(options: BracketGuidePreferences): NativeInterest {
        lastOptions = options
        reconcileCapability(options)
        return NativeInterest(episode, !closed && capable == true && !shown && !state.suppressedForCurrentConflict)
    }

    override fun settingsChanged(previous: BracketGuidePreferences, current: BracketGuidePreferences) {
        lastOptions = current
        reconcileCapability(current)
    }

    override fun changesApplied() = reconcileCapability(lastOptions)

    private fun reconcileCapability(options: BracketGuidePreferences) {
        val next = options.enabled && options.showActiveGuide && options.showVerticalGuide &&
            CodeInsightSettings.getInstance().HIGHLIGHT_BRACES
        if (capable == null) {
            capable = next
            return
        }
        if (capable == next) return
        capable = next
        episode++
        shown = false
        updateState { Suppression() }
        notification?.expire()
        notification = null
    }

    fun accept(editor: Editor, evidence: NativeConflictEvidence) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val current = interest(lastOptions)
        if (closed || evidence.episode != current.episode || !current.enabled || editor.isDisposed) return
        val project = editor.project?.takeUnless { it.isDisposed } ?: return
        val acceptedEpisode = episode
        val next = Notification(
            "Bracket Pair Guides Native Visual Conflict",
            "IntelliJ may emphasize an adjacent guide",
            "Matched brace or Current scope may emphasize a nearby IntelliJ guide or marker beside Bracket Pair Guides.",
            NotificationType.INFORMATION,
        )
            .addAction(
                NotificationAction.createSimpleExpiring("Review settings") {
                    ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) {
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project,
                                BracketGuideSettingsPage.DISPLAY_NAME,
                            )
                        }
                    }, ModalityState.nonModal())
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Don't warn again for this conflict") {
                    if (!closed && episode == acceptedEpisode) updateState { Suppression(true) }
                },
            )
        // Reserve before external SDK effects; failure restores this episode's eligibility.
        shown = true
        notification = next
        next.whenExpired { if (notification === next) notification = null }
        try {
            next.notify(project)
        } catch (failure: Throwable) {
            if (episode == acceptedEpisode && notification === next) {
                shown = false
                notification = null
            }
            next.expire()
            throw failure
        }
    }

    override fun dispose() {
        closed = true
        episode++
        notification?.expire()
        notification = null
    }
}
