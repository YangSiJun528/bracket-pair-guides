package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.application.options.editor.EditorOptionsListener
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.analysis.intellij.AnalysisReadEpoch
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.settings.NativeGuideConflictSettingsListener
import com.sijunyang.bracketpairguides.settings.ui.BracketGuideSettingsPage
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publishes one native-guide advisory per application session and conflict episode. */
@State(
    name = "BracketPairGuidesNativeGuideConflictNotification",
    storages = [Storage("bracket-pair-guides-native-guide-notification.xml")],
)
internal class NativeGuideConflictNotification internal constructor(
    private val preferences: () -> BracketGuidePreferences,
    private val captureConflict: (Editor, BracketGuide, BracketGuidePreferences) -> NativeConflictProbe?,
    private val createNotification: (Project, () -> Unit) -> Notification,
    private val schedule: (() -> Unit) -> Unit,
    private val nativeHighlightingEnabled: () -> Boolean = { true },
    private val showNotification: (Notification, Project) -> Unit = { _, _ -> },
    subscribeToEditorSettings: (EditorOptionsListener, Disposable) -> Unit = { _, _ -> },
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    subscribeToCarets: (CaretListener, Disposable) -> Unit = { _, _ -> },
) : SerializablePersistentStateComponent<NativeGuideConflictNotification.NotificationState>(
    NotificationState(),
),
    NativeGuideConflictSettingsListener,
    EditorOptionsListener,
    Disposable {
    private val ownedJob = SupervisorJob(scope.coroutineContext[Job])
    private val workerScope = CoroutineScope(scope.coroutineContext + ownedJob + Dispatchers.Default)

    @Suppress("unused")
    constructor(scope: CoroutineScope) : this(
        preferences = { BracketGuideSettings.getInstance().options },
        captureConflict = { editor, guide, preferences ->
            if (EditorGuideSessions.get(editor)?.drawsGuides != true) {
                null
            } else {
                NativeGuideConflictDetector.captureConflict(editor, guide, preferences)?.let { probe ->
                    object : NativeConflictProbe {
                        override suspend fun inspect(checkCanceled: () -> Unit): Boolean = probe.inspect(checkCanceled)
                        override fun isCurrent(): Boolean = EditorGuideSessions.get(editor)?.drawsGuides == true && probe.isCurrent()
                    }
                }
            }
        },
        createNotification = { project, suppressCurrentConflict ->
            NativeGuideConflictBalloon.create(project, suppressCurrentConflict)
        },
        schedule = { task -> ApplicationManager.getApplication().invokeLater { task() } },
        nativeHighlightingEnabled = { CodeInsightSettings.getInstance().HIGHLIGHT_BRACES },
        showNotification = { notification, project -> notification.notify(project) },
        scope = scope,
        subscribeToCarets = { listener, parentDisposable ->
            EditorFactory.getInstance().eventMulticaster.addCaretListener(listener, parentDisposable)
        },
        subscribeToEditorSettings = { listener, parentDisposable ->
            ApplicationManager.getApplication().messageBus.connect(parentDisposable).apply {
                subscribe(EditorOptionsListener.OPTIONS_PANEL_TOPIC, listener)
            }
        },
    )

    init {
        subscribeToEditorSettings(this, this)
        subscribeToCarets(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    ApplicationManager.getApplication().assertIsDispatchThread()
                    if (disposed || event.editor.isDisposed) return
                    if (event.caret?.let { it !== event.editor.caretModel.primaryCaret } == true) return
                    caretMoved(event.editor)
                }

                override fun caretAdded(event: CaretEvent) = caretPositionChanged(event)

                override fun caretRemoved(event: CaretEvent) {
                    ApplicationManager.getApplication().assertIsDispatchThread()
                    if (!disposed && !event.editor.isDisposed) caretMoved(event.editor)
                }
            },
            this,
        )
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
        synchronized(pendingLock) { settingsGeneration++; revokeInspectionsLocked() }
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
                disposed = true
                revokeInspectionsLocked()
                activeNotification.also { activeNotification = null }
            }
        ownedJob.cancel()
        notificationToExpire?.expire()
    }

    fun consider(editor: Editor, guide: BracketGuide) {
        if (!EditorEffectGuard.allowsEffects()) return
        // This callback runs from editor painting. The common suppressed and
        // already-shown states must stay allocation-free instead of constructing
        // and scheduling a candidate per repaint.
        if (isAdvisoryBlocked() || readNativeHighlightingEnabled() != true) return
        if (editor.isDisposed) return
        val project = editor.project?.takeUnless(Project::isDisposed) ?: return
        var previousJob: Job? = null
        val shouldSchedule = synchronized(pendingLock) {
            if (isAdvisoryBlockedLocked()) {
                false
            } else {
                val stamp = editor.document.modificationStamp
                val caret = editor.caretModel.primaryCaret.offset
                val highlighter = editor.highlighter
                val caretGeneration = caretGenerations.getOrPut(editor) { 0L }
                val existing = latestCandidates[editor]
                if (existing != null && existing.guide == guide && existing.documentStamp == stamp &&
                    existing.caretOffset == caret && existing.highlighter === highlighter &&
                    existing.caretGeneration == caretGeneration && existing.episodeGeneration == episodeGeneration && existing.settingsGeneration == settingsGeneration &&
                    existing.readEpoch == readEpoch.current) {
                    if (pendingCandidates[editor] === existing && !dispatchScheduled) {
                        dispatchScheduled = true
                        true
                    } else false
                } else {
                    val candidate = Candidate(editor, guide, project, stamp, caret, highlighter, caretGeneration, episodeGeneration, settingsGeneration, readEpoch.current)
                    latestCandidates[editor] = candidate
                    pendingCandidates[editor] = candidate
                    previousJob = inFlightJobs[editor]
                    if (dispatchScheduled) false else {
                        dispatchScheduled = true
                        true
                    }
                }
            }
        }
        previousJob?.cancel()
        if (shouldSchedule) dispatch()
    }

    /** Revokes the native service's own proof on primary-caret movement, including away and back. */
    internal fun caretMoved(editor: Editor) {
        val job = synchronized(pendingLock) {
            caretGenerations[editor]?.let { caretGenerations[editor] = it + 1L }
            pendingCandidates.remove(editor)
            latestCandidates.remove(editor)
            inFlightJobs[editor]
        }
        job?.cancel()
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
        ApplicationManager.getApplication().assertIsDispatchThread()
        val candidates = synchronized(pendingLock) {
            dispatchScheduled = false
            if (isAdvisoryBlockedLocked()) {
                pendingCandidates.clear()
                emptyList()
            } else {
                pendingCandidates.values.toList().also { pendingCandidates.clear() }
            }
        }
        for (candidate in candidates) startInspection(candidate)
    }

    private fun startInspection(candidate: Candidate) {
        if (!candidate.isCurrent() || !isLatest(candidate)) {
            releaseCandidate(candidate)
            return
        }
        val current = readPreferences()
        if (current == null) {
            releaseCandidate(candidate)
            return
        }
        val probe = try {
            captureConflict(candidate.editor, candidate.guide, current)
        } catch (error: ProcessCanceledException) {
            releaseCandidate(candidate)
            throw error
        } catch (error: RuntimeException) {
            releaseCandidate(candidate)
            LOG.warn("Could not capture the native guide conflict", error)
            return
        }
        if (probe == null) {
            releaseCandidate(candidate)
            return
        }
        val job = workerScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val context = currentCoroutineContext()
            try {
                val conflict = probe.inspect {
                    context.ensureActive()
                    ProgressManager.checkCanceled()
                    if (candidate.editor.isDisposed || candidate.project.isDisposed || !isLatest(candidate)) throw StaleInspection()
                }
                withContext(Dispatchers.EDT) {
                    acceptInspection(candidate, current, probe, conflict)
                }
            } catch (_: StaleInspection) {
                // A newer paint or caret/episode change revoked this proof.
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: RuntimeException) {
                LOG.warn("Could not inspect the native guide conflict", error)
            }
        }
        synchronized(pendingLock) { inFlightJobs[candidate.editor] = job }
        job.invokeOnCompletion {
            synchronized(pendingLock) {
                if (inFlightJobs[candidate.editor] === job) inFlightJobs.remove(candidate.editor)
            }
            releaseCandidate(candidate)
        }
        job.start()
    }

    private fun isLatest(candidate: Candidate): Boolean = synchronized(pendingLock) {
        !isAdvisoryBlockedLocked() && candidate.episodeGeneration == episodeGeneration &&
            latestCandidates[candidate.editor] === candidate &&
            caretGenerations[candidate.editor] == candidate.caretGeneration &&
            candidate.settingsGeneration == settingsGeneration && candidate.readEpoch == readEpoch.current
    }

    private fun releaseCandidate(candidate: Candidate) {
        synchronized(pendingLock) {
            if (latestCandidates[candidate.editor] === candidate) latestCandidates.remove(candidate.editor)
        }
    }

    /** Only EDT may accept a completed proof and perform the original notification transaction. */
    private fun acceptInspection(
        candidate: Candidate,
        inspectedPreferences: BracketGuidePreferences,
        probe: NativeConflictProbe,
        conflict: Boolean,
    ): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!conflict || !candidate.isCurrent() || !isLatest(candidate) ||
            readNativeHighlightingEnabled() != true || readPreferences() != inspectedPreferences || !probe.isCurrent()) return false

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
                    disposed || candidate.settingsGeneration != settingsGeneration ||
                    candidate.readEpoch != readEpoch.current || latestCandidates[candidate.editor] !== candidate ||
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
        synchronized(pendingLock) {
            pendingCandidates.clear()
            latestCandidates.clear()
            inFlightJobs.filterKeys { it !== candidate.editor }.values.forEach { it.cancel() }
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
                revokeInspectionsLocked()
                activeNotification.also { activeNotification = null }
            }
        notificationToExpire?.expire()
    }

    private fun clearPublicationReservation(generation: Long) {
        synchronized(pendingLock) {
            if (publishingGeneration == generation) publishingGeneration = null
        }
    }

    private fun isAdvisoryBlocked(): Boolean =
        disposed || mutedForDriverSession || shownThisSession || state.suppressedForCurrentConflict

    private fun isAdvisoryBlockedLocked(): Boolean = disposed || mutedForDriverSession ||
        shownThisSession ||
        state.suppressedForCurrentConflict ||
        publishingGeneration == episodeGeneration

    private fun resetConflictEpisodeLocked(): Notification? {
        episodeGeneration++
        shownThisSession = false
        revokeInspectionsLocked()
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
                revokeInspectionsLocked()
                activeNotification.also { activeNotification = null }
            }
        notificationToExpire?.expire()
    }

    private fun revokeInspectionsLocked() {
        pendingCandidates.clear()
        latestCandidates.clear()
        inFlightJobs.values.forEach { it.cancel() }
        inFlightJobs.clear()
    }

    private class StaleInspection : RuntimeException(null, null, false, false)

    private data class Candidate(
        val editor: Editor,
        val guide: BracketGuide,
        val project: Project,
        val documentStamp: Long,
        val caretOffset: Int,
        val highlighter: Any,
        val caretGeneration: Long,
        val episodeGeneration: Long,
        val settingsGeneration: Long,
        val readEpoch: Long,
    ) {
        fun isCurrent(): Boolean = !editor.isDisposed &&
            !project.isDisposed &&
            editor.project === project &&
            editor.document.modificationStamp == documentStamp &&
            editor.highlighter === highlighter &&
            editor.caretModel.primaryCaret.offset == caretOffset
    }

    private val readEpoch = ApplicationManager.getApplication().getService(AnalysisReadEpoch::class.java)
    private var settingsGeneration = 0L
    private val pendingLock = Any()
    private val pendingCandidates = IdentityHashMap<Editor, Candidate>()
    private val latestCandidates = IdentityHashMap<Editor, Candidate>()
    private val inFlightJobs = IdentityHashMap<Editor, Job>()
    private val caretGenerations = WeakHashMap<Editor, Long>()
    @Volatile private var disposed = false
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
