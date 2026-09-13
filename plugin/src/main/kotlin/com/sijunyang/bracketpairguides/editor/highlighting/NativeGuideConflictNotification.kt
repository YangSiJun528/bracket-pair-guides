package com.sijunyang.bracketpairguides.editor.highlighting

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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.settings.NativeGuideConflictSettingsListener
import com.sijunyang.bracketpairguides.settings.ui.BracketGuideSettingsPage
import java.util.IdentityHashMap

/** Publishes one native-guide advisory per application session and conflict episode. */
@State(
    name = "BracketPairGuidesNativeGuideConflictNotification",
    storages = [Storage("bracket-pair-guides-native-guide-notification.xml")],
)
internal class NativeGuideConflictNotification internal constructor(
    private val preferences: () -> BracketGuidePreferences,
    private val isConflict: (Editor, BracketGuide, BracketGuidePreferences) -> Boolean,
    private val createNotification: (Project, () -> Unit) -> Notification,
    private val schedule: (() -> Unit) -> Unit,
    private val nativeHighlightingEnabled: () -> Boolean = { true },
    private val showNotification: (Notification, Project) -> Unit = { _, _ -> },
    subscribeToEditorSettings: (EditorOptionsListener, Disposable) -> Unit = { _, _ -> },
) : SerializablePersistentStateComponent<NativeGuideConflictNotification.NotificationState>(
    NotificationState(),
),
    NativeGuideConflictSettingsListener,
    EditorOptionsListener,
    Disposable {
    @Suppress("unused")
    constructor() : this(
        preferences = { BracketGuideSettings.getInstance().options },
        isConflict = NativeGuideConflictDetector::isConflict,
        createNotification = { project, suppressCurrentConflict ->
            NativeGuideConflictBalloon.create(project, suppressCurrentConflict)
        },
        schedule = { task -> ApplicationManager.getApplication().invokeLater { task() } },
        nativeHighlightingEnabled = { CodeInsightSettings.getInstance().HIGHLIGHT_BRACES },
        showNotification = { notification, project -> notification.notify(project) },
        subscribeToEditorSettings = { listener, parentDisposable ->
            ApplicationManager.getApplication().messageBus.connect(parentDisposable).apply {
                subscribe(EditorOptionsListener.OPTIONS_PANEL_TOPIC, listener)
            }
        },
    )

    init {
        subscribeToEditorSettings(this, this)
    }

    override fun loadState(state: NotificationState) {
        // `published` in 0.0.5 development builds recorded an automatic display,
        // not an explicit opt-out. Do not silently migrate it into suppression.
        super.loadState(
            NotificationState(
                suppressedForCurrentConflict = state.suppressedForCurrentConflict,
            ),
        )
    }

    override fun settingsChanged(previous: BracketGuidePreferences, current: BracketGuidePreferences) {
        val nativeEnabled = readNativeHighlightingEnabled() ?: return
        val conflictCapable = current.hasPluginConflictSurface() && nativeEnabled
        if (knownConflictCapability == conflictCapable) return

        val notificationToExpire =
            synchronized(pendingLock) {
                val observedBoundary =
                    knownConflictCapability?.let { known -> known != conflictCapable }
                        ?: (
                            !conflictCapable ||
                                previous.hasConfiguredConflictPotential() !=
                                current.hasConfiguredConflictPotential()
                            )
                knownConflictCapability = conflictCapable
                if (!observedBoundary) {
                    null
                } else {
                    resetConflictEpisodeLocked()
                }
            }
        notificationToExpire?.expire()
    }

    override fun changesApplied() {
        val current = readPreferences() ?: return
        settingsChanged(current, current)
    }

    override fun dispose() {
        val notificationToExpire =
            synchronized(pendingLock) {
                pendingCandidates.clear()
                activeNotification.also { activeNotification = null }
            }
        notificationToExpire?.expire()
    }

    fun consider(editor: Editor, guide: BracketGuide) {
        // This callback runs from editor painting. The common suppressed and
        // already-shown states must stay allocation-free instead of constructing
        // and scheduling a candidate per repaint.
        if (isAdvisoryBlocked() || readNativeHighlightingEnabled() != true) return
        if (editor.isDisposed) return
        val project = editor.project?.takeUnless(Project::isDisposed) ?: return
        val shouldSchedule =
            synchronized(pendingLock) {
                if (isAdvisoryBlockedLocked()) {
                    false
                } else {
                    val candidate =
                        Candidate(
                            editor = editor,
                            guide = guide,
                            project = project,
                            documentStamp = editor.document.modificationStamp,
                            caretOffset = editor.caretModel.primaryCaret.offset,
                            episodeGeneration = episodeGeneration,
                        )
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

    private fun readNativeHighlightingEnabled(): Boolean? = try {
        nativeHighlightingEnabled()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: RuntimeException) {
        LOG.warn("Could not inspect the native highlighting state", error)
        null
    }

    private fun readPreferences(): BracketGuidePreferences? = try {
        preferences()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: RuntimeException) {
        LOG.warn("Could not inspect the native guide conflict settings", error)
        null
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
                if (isAdvisoryBlockedLocked()) {
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
                    if (isAdvisoryBlockedLocked()) {
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

    /** Returns true only after the current session's advisory was published. */
    private fun inspect(candidate: Candidate): Boolean {
        if (!candidate.isCurrent() || !isCurrentEpisode(candidate.episodeGeneration)) return false
        val current = readPreferences() ?: return false
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

        val generation = candidate.episodeGeneration
        val mayPublish =
            synchronized(pendingLock) {
                if (
                    generation != episodeGeneration ||
                    isAdvisoryBlockedLocked()
                ) {
                    false
                } else {
                    publishingGeneration = generation
                    true
                }
            }
        if (!mayPublish) return false

        val notification =
            try {
                createNotification(candidate.project) { suppressCurrentConflict(generation) }
            } catch (error: ProcessCanceledException) {
                clearPublicationReservation(generation)
                throw error
            } catch (error: RuntimeException) {
                clearPublicationReservation(generation)
                LOG.warn("Could not create the native guide conflict notification", error)
                return false
            }
        notification.whenExpired {
            synchronized(pendingLock) {
                if (activeNotification === notification) activeNotification = null
            }
        }
        val accepted =
            synchronized(pendingLock) {
                if (publishingGeneration == generation) publishingGeneration = null
                if (
                    generation != episodeGeneration ||
                    mutedForDriverSession ||
                    state.suppressedForCurrentConflict
                ) {
                    false
                } else {
                    shownThisSession = true
                    activeNotification = notification
                    true
                }
            }
        if (!accepted) {
            notification.expire()
            return false
        }
        try {
            showNotification(notification, candidate.project)
        } catch (error: ProcessCanceledException) {
            rollBackFailedDisplay(notification, generation)
            throw error
        } catch (error: RuntimeException) {
            rollBackFailedDisplay(notification, generation)
            LOG.warn("Could not publish the native guide conflict notification", error)
            return false
        }
        return true
    }

    private fun rollBackFailedDisplay(notification: Notification, generation: Long) {
        synchronized(pendingLock) {
            if (
                generation == episodeGeneration &&
                activeNotification === notification &&
                !state.suppressedForCurrentConflict
            ) {
                activeNotification = null
                shownThisSession = false
            }
        }
        notification.expire()
    }

    private fun suppressCurrentConflict(generation: Long) {
        val notificationToExpire =
            synchronized(pendingLock) {
                if (generation != episodeGeneration) return
                if (!state.suppressedForCurrentConflict) {
                    updateState {
                        NotificationState(suppressedForCurrentConflict = true)
                    }
                }
                pendingCandidates.clear()
                activeNotification.also { activeNotification = null }
            }
        notificationToExpire?.expire()
    }

    private fun clearPublicationReservation(generation: Long) {
        synchronized(pendingLock) {
            if (publishingGeneration == generation) publishingGeneration = null
        }
    }

    private fun isCurrentEpisode(generation: Long): Boolean = synchronized(pendingLock) {
        generation == episodeGeneration && !isAdvisoryBlockedLocked()
    }

    private fun isAdvisoryBlocked(): Boolean =
        mutedForDriverSession || shownThisSession || state.suppressedForCurrentConflict

    private fun isAdvisoryBlockedLocked(): Boolean = mutedForDriverSession ||
        shownThisSession ||
        state.suppressedForCurrentConflict ||
        publishingGeneration == episodeGeneration

    private fun resetConflictEpisodeLocked(): Notification? {
        episodeGeneration++
        shownThisSession = false
        pendingCandidates.clear()
        if (state.suppressedForCurrentConflict) {
            updateState { NotificationState() }
        }
        return activeNotification.also { activeNotification = null }
    }

    /** Prevents test-driver balloons without persisting or changing episode state. */
    internal fun muteForDriverSession() {
        val notificationToExpire =
            synchronized(pendingLock) {
                mutedForDriverSession = true
                pendingCandidates.clear()
                activeNotification.also { activeNotification = null }
            }
        notificationToExpire?.expire()
    }

    private data class Candidate(
        val editor: Editor,
        val guide: BracketGuide,
        val project: Project,
        val documentStamp: Long,
        val caretOffset: Int,
        val episodeGeneration: Long,
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

    @Volatile private var shownThisSession = false

    @Volatile private var mutedForDriverSession = false

    @Volatile private var knownConflictCapability: Boolean? = null

    private var episodeGeneration = 0L
    private var publishingGeneration: Long? = null
    private var activeNotification: Notification? = null

    internal data class NotificationState(
        @JvmField @field:Property val suppressedForCurrentConflict: Boolean = false,
        /** Legacy XML field. A true value represented an automatic display and is ignored. */
        @JvmField @field:Property val published: Boolean? = null,
    )

    companion object {
        private val LOG = Logger.getInstance(NativeGuideConflictNotification::class.java)

        fun getInstance(): NativeGuideConflictNotification = ApplicationManager.getApplication()
            .getService(NativeGuideConflictSettingsListener::class.java) as NativeGuideConflictNotification

        private fun BracketGuidePreferences.hasPluginConflictSurface(): Boolean =
            enabled && showActiveGuide && showVerticalGuide

        private fun BracketGuidePreferences.hasConfiguredConflictPotential(): Boolean = hasPluginConflictSurface() &&
            (
                !intelliJIntegration.manageNativeVisuals ||
                    intelliJIntegration.nativeHighlightMode !=
                    NativeHighlightMode.SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE
                )
    }
}

/** Constructs the informational balloon without changing any user setting. */
internal object NativeGuideConflictBalloon {
    fun create(
        project: Project,
        suppressCurrentConflict: () -> Unit = {},
        openSettings: (Project) -> Unit = ::openIntegrationSettings,
    ): Notification = Notification(GROUP_ID, TITLE, CONTENT, NotificationType.INFORMATION)
        .addAction(
            NotificationAction.createSimpleExpiring(ACTION_TEXT) {
                openSettings(project)
            },
        )
        .addAction(
            NotificationAction.createSimpleExpiring(SUPPRESS_ACTION_TEXT) {
                suppressCurrentConflict()
            },
        )

    private fun openIntegrationSettings(project: Project) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        BracketGuideSettingsPage.DISPLAY_NAME,
                    )
                }
            },
            ModalityState.nonModal(),
        )
    }

    internal const val GROUP_ID = "Bracket Pair Guides Native Visual Conflict"
    internal const val TITLE = "IntelliJ may emphasize an adjacent guide"
    internal const val CONTENT =
        "Matched brace or Current scope may emphasize a nearby IntelliJ guide or marker " +
            "beside Bracket Pair Guides. " +
            "Review the integration settings if this is unintended."
    internal const val ACTION_TEXT = "Review settings"
    internal const val SUPPRESS_ACTION_TEXT = "Don't warn again for this conflict"
}
