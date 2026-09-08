package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.settings.ui.BracketGuideSettingsPage
import java.util.IdentityHashMap

/** Publishes the application-wide native-guide advisory at most once. */
@State(
    name = "BracketPairGuidesNativeGuideConflictNotification",
    storages = [Storage("bracket-pair-guides-native-guide-notification.xml")],
)
internal class NativeGuideConflictNotification internal constructor(
    private val preferences: () -> BracketGuidePreferences,
    private val isConflict: (Editor, BracketGuide, BracketGuidePreferences) -> Boolean,
    private val publish: (Project) -> Unit,
    private val schedule: (() -> Unit) -> Unit,
) : SerializablePersistentStateComponent<NativeGuideConflictNotification.NotificationState>(
    NotificationState(),
) {
    @Suppress("unused")
    constructor() : this(
        preferences = { BracketGuideSettings.getInstance().options },
        isConflict = NativeGuideConflictDetector::isConflict,
        publish = NativeGuideConflictBalloon::publish,
        schedule = { task -> ApplicationManager.getApplication().invokeLater { task() } },
    )

    fun consider(editor: Editor, guide: BracketGuide) {
        if (editor.isDisposed) return
        val project = editor.project?.takeUnless(Project::isDisposed) ?: return
        val candidate =
            Candidate(
                editor = editor,
                guide = guide,
                project = project,
                documentStamp = editor.document.modificationStamp,
                caretOffset = editor.caretModel.primaryCaret.offset,
            )
        val shouldSchedule =
            synchronized(pendingLock) {
                if (state.published) {
                    false
                } else {
                    // Repaints can report the same renderer many times in one
                    // event-loop turn. Preserve one latest proof per editor so
                    // an unrelated editor cannot overwrite a real conflict.
                    pendingCandidates[editor] = candidate
                    if (dispatchScheduled) {
                        false
                    } else {
                        dispatchScheduled = true
                        true
                    }
                }
            }
        if (shouldSchedule) dispatch()
    }

    private fun dispatch() {
        try {
            schedule(::drain)
        } catch (error: ProcessCanceledException) {
            synchronized(pendingLock) { dispatchScheduled = false }
            throw error
        } catch (error: RuntimeException) {
            // A paint callback must not fail because the deferred advisory
            // could not be queued. A later paint may retry the pending proof.
            synchronized(pendingLock) { dispatchScheduled = false }
            LOG.warn("Could not schedule the native guide conflict inspection", error)
        }
    }

    private fun drain() {
        val candidates =
            synchronized(pendingLock) {
                if (state.published) {
                    pendingCandidates.clear()
                    emptyList()
                } else {
                    pendingCandidates.values.toList().also { pendingCandidates.clear() }
                }
            }

        try {
            for (candidate in candidates) {
                if (inspect(candidate)) break
            }
        } finally {
            val shouldSchedule =
                synchronized(pendingLock) {
                    if (state.published) {
                        pendingCandidates.clear()
                        dispatchScheduled = false
                        false
                    } else if (pendingCandidates.isNotEmpty()) {
                        true
                    } else {
                        dispatchScheduled = false
                        false
                    }
                }
            if (shouldSchedule) dispatch()
        }
    }

    /** Returns true only after the application-wide advisory was published. */
    private fun inspect(candidate: Candidate): Boolean {
        if (!candidate.isCurrent()) return false
        val current =
            try {
                preferences()
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: RuntimeException) {
                LOG.warn("Could not inspect the native guide conflict", error)
                return false
            }
        val conflict =
            try {
                isConflict(candidate.editor, candidate.guide, current)
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: RuntimeException) {
                LOG.warn("Could not inspect the native guide conflict", error)
                return false
            }
        if (!conflict || !candidate.isCurrent()) return false

        try {
            publish(candidate.project)
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: RuntimeException) {
            LOG.warn("Could not publish the native guide conflict notification", error)
            return false
        }
        synchronized(pendingLock) {
            if (!state.published) updateState { NotificationState(published = true) }
        }
        return true
    }

    private data class Candidate(
        val editor: Editor,
        val guide: BracketGuide,
        val project: Project,
        val documentStamp: Long,
        val caretOffset: Int,
    ) {
        fun isCurrent(): Boolean = !editor.isDisposed &&
            !project.isDisposed &&
            editor.project === project &&
            editor.document.modificationStamp == documentStamp &&
            editor.caretModel.primaryCaret.offset == caretOffset
    }

    private val pendingLock = Any()
    private val pendingCandidates = IdentityHashMap<Editor, Candidate>()
    private var dispatchScheduled = false

    internal data class NotificationState(@JvmField @field:Property val published: Boolean = false)

    companion object {
        private val LOG = Logger.getInstance(NativeGuideConflictNotification::class.java)

        fun getInstance(): NativeGuideConflictNotification =
            ApplicationManager.getApplication().getService(NativeGuideConflictNotification::class.java)
    }
}

/** Constructs the informational balloon without changing any user setting. */
internal object NativeGuideConflictBalloon {
    fun create(project: Project, openSettings: (Project) -> Unit = ::openIntegrationSettings): Notification =
        Notification(GROUP_ID, TITLE, CONTENT, NotificationType.INFORMATION)
            .addAction(
                NotificationAction.createSimpleExpiring(ACTION_TEXT) {
                    openSettings(project)
                },
            )

    fun publish(project: Project) {
        create(project).notify(project)
    }

    private fun openIntegrationSettings(project: Project) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        BracketGuideSettingsPage::class.java,
                    )
                }
            },
            ModalityState.nonModal(),
        )
    }

    internal const val GROUP_ID = "Bracket Pair Guides Native Visual Conflict"
    internal const val TITLE = "IntelliJ guide highlighting may overlap"
    internal const val CONTENT =
        "IntelliJ highlighting may draw another line beside Bracket Pair Guides. " +
            "Review the integration settings if this is unintended."
    internal const val ACTION_TEXT = "Review settings"
}
