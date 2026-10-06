package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.application.options.editor.EditorOptionsListener
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.settings.NativeGuideConflictSettingsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeGuideConflictNotificationTest : BasePlatformTestCase() {
    private lateinit var testScope: CoroutineScope

    override fun tearDown() {
        try {
            testScope.cancel()
            awaitWorkers()
        } finally {
            super.tearDown()
        }
    }

    private fun awaitWorkers() {
        PlatformTestUtil.waitWithEventsDispatching("Native conflict workers did not finish", {
            testScope.coroutineContext[Job]!!.children.all { ownedRoot -> ownedRoot.children.none() }
        }, 10)
    }
    override fun setUp() {
        super.setUp()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
        myFixture.configureByText(
            "Conflict.java",
            "class Conflict {\n    void run() {\n    }\n}",
        )
    }

    fun testConflictPublishesOncePerSessionWithoutPersistingOrdinaryDisplay() {
        val expectedPreferences = CONFLICT_PREFERENCES
        val scheduler = ManualScheduler()
        var conflictChecks = 0
        var publications = 0
        var publishedNotification: Notification? = null
        val latestGuide = MULTILINE_GUIDE.copy(anchorLine = 2)
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { expectedPreferences },
                captureConflict = { editor, guide, preferences ->
                    NativeConflictProbe { _ ->
                        assertThat(editor).isSameAs(myFixture.editor)
                        assertThat(guide).isEqualTo(latestGuide)
                        assertThat(preferences).isSameAs(expectedPreferences)
                        conflictChecks++
                        true
                    }
                },
                createNotification = { publishedProject, _ ->
                    assertThat(publishedProject).isSameAs(project)
                    publications++
                    testNotification().also { publishedNotification = it }
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        notification.consider(myFixture.editor, latestGuide)

        assertThat(scheduler.pendingCount).isEqualTo(1)
        assertThat(conflictChecks).isZero()
        assertThat(publications).isZero()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()

        scheduler.runNext()

        assertThat(conflictChecks).isEqualTo(1)
        assertThat(publications).isEqualTo(1)
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()

        publishedNotification?.expire()
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        assertThat(scheduler.pendingCount).isZero()

        var restoredPublications = 0
        val restoredScheduler = ManualScheduler()
        val restored =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { expectedPreferences },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    restoredPublications++
                    testNotification()
                },
                schedule = restoredScheduler::schedule,
                nativeHighlightingEnabled = {
                    true
                },
            )
        val serialized = XmlSerializer.serialize(notification.state)
        restored.loadState(
            XmlSerializer.deserialize(
                serialized,
                NativeGuideConflictNotification.NotificationState::class.java,
            ),
        )

        restored.consider(myFixture.editor, MULTILINE_GUIDE)
        restoredScheduler.runNext()

        assertThat(restored.state.suppressedForCurrentConflict).isFalse()
        assertThat(restoredPublications).isEqualTo(1)
        assertThat(restoredScheduler.pendingCount).isZero()
    }

    fun testExplicitSuppressionSurvivesRestartUntilAConflictEpisodeEnds() {
        val scheduler = ManualScheduler()
        var suppressCurrentConflict: (() -> Unit)? = null
        var publishedNotification: Notification? = null
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, suppress ->
                    suppressCurrentConflict = suppress
                    testNotification().also { publishedNotification = it }
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        requireNotNull(suppressCurrentConflict).invoke()

        assertThat(notification.state.suppressedForCurrentConflict).isTrue()
        assertThat(publishedNotification?.isExpired).isTrue()
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        assertThat(scheduler.pendingCount).isZero()

        val restoredScheduler = ManualScheduler()
        var restoredPublications = 0
        val restored =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    restoredPublications++
                    testNotification()
                },
                schedule = restoredScheduler::schedule,
                nativeHighlightingEnabled = {
                    error("Explicit suppression must skip native-state inspection")
                },
            )
        restored.loadState(
            XmlSerializer.deserialize(
                XmlSerializer.serialize(notification.state),
                NativeGuideConflictNotification.NotificationState::class.java,
            ),
        )

        restored.consider(myFixture.editor, MULTILINE_GUIDE)
        assertThat(restoredScheduler.pendingCount).isZero()
        assertThat(restoredPublications).isZero()

        var nativeHighlightingEnabled = true
        val rearmed =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    restoredPublications++
                    testNotification()
                },
                schedule = restoredScheduler::schedule,
                nativeHighlightingEnabled = { nativeHighlightingEnabled },
            )
        rearmed.loadState(notification.state)

        rearmed.settingsChanged(CONFLICT_PREFERENCES, SAFE_PREFERENCES)
        assertThat(rearmed.state.suppressedForCurrentConflict).isFalse()
        rearmed.settingsChanged(SAFE_PREFERENCES, CONFLICT_PREFERENCES)
        rearmed.consider(myFixture.editor, MULTILINE_GUIDE)
        restoredScheduler.runNext()

        assertThat(restoredPublications).isEqualTo(1)

        nativeHighlightingEnabled = false
        rearmed.changesApplied()
        nativeHighlightingEnabled = true
        rearmed.changesApplied()
        rearmed.consider(myFixture.editor, MULTILINE_GUIDE)
        restoredScheduler.runNext()

        assertThat(restoredPublications).isEqualTo(2)
    }

    fun testConflictCapableSettingChangesDoNotRearmTheSameEpisode() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    publications++
                    testNotification()
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        val recolored = CONFLICT_PREFERENCES.copy(colorBracketTokens = false)
        notification.settingsChanged(CONFLICT_PREFERENCES, recolored)
        notification.settingsChanged(
            recolored,
            recolored.copy(
                intelliJIntegration =
                IntelliJIntegrationPreferences(
                    manageNativeVisuals = true,
                    nativeHighlightMode = NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                ),
            ),
        )
        notification.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(publications).isEqualTo(1)
        assertThat(scheduler.pendingCount).isZero()
    }

    fun testEditorOptionsApplyTopicRearmsAfterNativeHighlightingChanges() {
        val scheduler = ManualScheduler()
        var nativeHighlightingEnabled = true
        var suppressCurrentConflict: (() -> Unit)? = null
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, suppress ->
                    publications++
                    suppressCurrentConflict = suppress
                    testNotification()
                },
                schedule = scheduler::schedule,
                nativeHighlightingEnabled = { nativeHighlightingEnabled },
                subscribeToEditorSettings = { listener, parentDisposable ->
                    ApplicationManager.getApplication().messageBus.connect(parentDisposable).apply {
                        subscribe(EditorOptionsListener.OPTIONS_PANEL_TOPIC, listener)
                    }
                },
            )
        Disposer.register(testRootDisposable, notification)

        notification.settingsChanged(CONFLICT_PREFERENCES, CONFLICT_PREFERENCES)
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        requireNotNull(suppressCurrentConflict).invoke()

        nativeHighlightingEnabled = false
        ApplicationManager.getApplication().messageBus
            .syncPublisher(EditorOptionsListener.OPTIONS_PANEL_TOPIC)
            .changesApplied()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()

        nativeHighlightingEnabled = true
        ApplicationManager.getApplication().messageBus
            .syncPublisher(EditorOptionsListener.OPTIONS_PANEL_TOPIC)
            .changesApplied()
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        assertThat(publications).isEqualTo(2)
    }

    fun testNativeEnableAndItsLaterPreferenceCorrectionAreOneEpisodeBoundary() {
        val scheduler = ManualScheduler()
        var currentPreferences = BracketGuidePreferences()
        var nativeHighlightingEnabled = false
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { currentPreferences },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    publications++
                    testNotification()
                },
                schedule = scheduler::schedule,
                nativeHighlightingEnabled = { nativeHighlightingEnabled },
            )

        notification.settingsChanged(currentPreferences, currentPreferences)
        nativeHighlightingEnabled = true
        notification.changesApplied()
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        val previous = currentPreferences
        currentPreferences = CONFLICT_PREFERENCES
        notification.settingsChanged(previous, currentPreferences)
        notification.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(publications).isEqualTo(1)
        assertThat(scheduler.pendingCount).isZero()
    }

    fun testEpisodeBoundaryDropsAQueuedCandidate() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    publications++
                    testNotification()
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        notification.settingsChanged(CONFLICT_PREFERENCES, SAFE_PREFERENCES)
        notification.settingsChanged(SAFE_PREFERENCES, CONFLICT_PREFERENCES)
        scheduler.runNext()

        assertThat(publications).isZero()

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        assertThat(publications).isEqualTo(1)
    }

    fun testLegacyPublishedStateDoesNotBecomeExplicitSuppression() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    publications++
                    testNotification()
                },
                schedule = scheduler::schedule,
            )
        val legacy =
            XmlSerializer.deserialize(
                XmlSerializer.serialize(
                    NativeGuideConflictNotification.NotificationState(published = true),
                ),
                NativeGuideConflictNotification.NotificationState::class.java,
            )

        notification.loadState(legacy)
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
        assertThat(notification.state.published).isNull()
        assertThat(publications).isEqualTo(1)
    }

    fun testDriverMuteSurvivesConflictEpisodeChangesWithoutPersistingSuppression() {
        val scheduler = ManualScheduler()
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { CONFLICT_PREFERENCES },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ -> error("A driver-muted advisory must not publish") },
                schedule = scheduler::schedule,
            )

        notification.muteForDriverSession()
        notification.settingsChanged(CONFLICT_PREFERENCES, SAFE_PREFERENCES)
        notification.settingsChanged(SAFE_PREFERENCES, CONFLICT_PREFERENCES)
        notification.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
        assertThat(scheduler.pendingCount).isZero()
    }

    fun testApplicationServiceFacadeResolvesTheInterfaceRegistration() {
        val service = NativeGuideConflictNotification.getInstance()

        assertThat(
            ApplicationManager.getApplication()
                .getService(NativeGuideConflictSettingsListener::class.java),
        ).isSameAs(service)
    }

    fun testSuppressedNativeHighlightSkipsCandidateWorkUntilItIsEnabled() {
        val scheduler = ManualScheduler()
        var nativeHighlightingEnabled = false
        var conflictChecks = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { BracketGuidePreferences() },
                captureConflict = { _, _, _ ->
                    NativeConflictProbe { _ ->
                        conflictChecks++
                        false
                    }
                },
                createNotification = { _, _ -> error("A non-conflict must not publish") },
                schedule = scheduler::schedule,
                nativeHighlightingEnabled = { nativeHighlightingEnabled },
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(scheduler.pendingCount).isZero()
        assertThat(conflictChecks).isZero()

        nativeHighlightingEnabled = true
        notification.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(scheduler.pendingCount).isEqualTo(1)
        scheduler.runNext()
        assertThat(conflictChecks).isEqualTo(1)
    }

    fun testNativeHighlightPreflightFailureDoesNotEscapeThePaintCallback() {
        val scheduler = ManualScheduler()
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { BracketGuidePreferences() },
                captureConflict = { _, _, _ ->
                    NativeConflictProbe { _ -> error("Preflight failure must skip detection") }
                },
                createNotification = { _, _ -> error("Preflight failure must not publish") },
                schedule = scheduler::schedule,
                nativeHighlightingEnabled = { error("synthetic native-state failure") },
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(scheduler.pendingCount).isZero()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
    }

    fun testConflictIsNotLostWhenAnotherEditorReportsNonConflictInTheSameTick() {
        val scheduler = ManualScheduler()
        val editorFactory = EditorFactory.getInstance()
        val nonConflictEditor =
            editorFactory.createEditor(
                editorFactory.createDocument("class NonConflict {}"),
                project,
            )
        val inspectedEditors = java.util.Collections.synchronizedList(mutableListOf<Editor>())
        var publications = 0
        try {
            val notification =
                NativeGuideConflictNotification(
                    scope = testScope,
                    preferences = { BracketGuidePreferences() },
                    captureConflict = { editor, _, _ ->
                        NativeConflictProbe { _ ->
                            inspectedEditors += editor
                            editor === myFixture.editor
                        }
                    },
                    createNotification = { _, _ ->
                        publications++
                        testNotification()
                    },
                    schedule = scheduler::schedule,
                )

            notification.consider(myFixture.editor, MULTILINE_GUIDE)
            notification.consider(nonConflictEditor, MULTILINE_GUIDE)

            assertThat(scheduler.pendingCount).isEqualTo(1)
            scheduler.runNext()

            assertThat(inspectedEditors).contains(myFixture.editor)
            assertThat(publications).isEqualTo(1)
            assertThat(notification.state.suppressedForCurrentConflict).isFalse()
        } finally {
            editorFactory.releaseEditor(nonConflictEditor)
        }
    }

    fun testFailedPublicationIsNotMarkedAndTheNextPresentationRetries() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { BracketGuidePreferences() },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> true } },
                createNotification = { _, _ ->
                    publications++
                    if (publications == 1) error("synthetic publish failure")
                    testNotification()
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        assertThat(publications).isEqualTo(2)
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
    }

    fun testNonConflictDoesNotPublishOrConsumeTheSessionWarning() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { BracketGuidePreferences() },
                captureConflict = { _, _, _ -> NativeConflictProbe { _ -> false } },
                createNotification = { _, _ ->
                    publications++
                    testNotification()
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        assertThat(publications).isZero()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
    }

    fun testCaretOrDocumentChangeDropsAStaleCandidateBeforeDetection() {
        val scheduler = ManualScheduler()
        var conflictChecks = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { BracketGuidePreferences() },
                captureConflict = { _, _, _ ->
                    NativeConflictProbe { _ ->
                        conflictChecks++
                        true
                    }
                },
                createNotification = { _, _ -> error("A stale candidate must not publish") },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        myFixture.editor.caretModel.moveToOffset(1)
        scheduler.runNext()

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        scheduler.runNext()

        assertThat(conflictChecks).isZero()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
    }

    fun testDisposedEditorDropsAQueuedCandidateBeforeDetection() {
        val scheduler = ManualScheduler()
        var conflictChecks = 0
        val notification =
            NativeGuideConflictNotification(
                scope = testScope,
                preferences = { BracketGuidePreferences() },
                captureConflict = { _, _, _ ->
                    NativeConflictProbe { _ ->
                        conflictChecks++
                        true
                    }
                },
                createNotification = { _, _ -> error("A disposed editor candidate must not publish") },
                schedule = scheduler::schedule,
            )
        val editorFactory = EditorFactory.getInstance()
        val disposableEditor =
            editorFactory.createEditor(
                editorFactory.createDocument("class Disposable {}"),
                project,
            )

        notification.consider(disposableEditor, MULTILINE_GUIDE)
        editorFactory.releaseEditor(disposableEditor)
        scheduler.runNext()

        assertThat(conflictChecks).isZero()
        assertThat(notification.state.suppressedForCurrentConflict).isFalse()
    }

    fun testBalloonOffersReviewAndCurrentConflictSuppressionWithoutChangingSettings() {
        var openedProject: Project? = null
        var suppressions = 0
        val settingsBeforeAction = BracketGuideSettings.getInstance().options
        val nativeBeforeAction =
            Triple(
                CodeInsightSettings.getInstance().HIGHLIGHT_BRACES,
                CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE,
                EditorSettingsExternalizable.getInstance().isIndentGuidesShown,
            )
        val notification =
            NativeGuideConflictBalloon.create(
                project = project,
                suppressCurrentConflict = { suppressions++ },
                openSettings = { selectedProject -> openedProject = selectedProject },
            )

        assertThat(notification.type).isEqualTo(NotificationType.INFORMATION)
        assertThat(notification.title).isEqualTo(NativeGuideConflictBalloon.TITLE)
        assertThat(notification.content).isEqualTo(NativeGuideConflictBalloon.CONTENT)
        assertThat(notification.actions.map { action -> action.templateText }).containsExactly(
            NativeGuideConflictBalloon.ACTION_TEXT,
            NativeGuideConflictBalloon.SUPPRESS_ACTION_TEXT,
        )

        Notification.fire(notification, notification.actions.first(), null)

        assertThat(openedProject).isSameAs(project)
        assertThat(suppressions).isZero()
        assertThat(notification.isExpired).isTrue()

        val suppressionNotification =
            NativeGuideConflictBalloon.create(
                project = project,
                suppressCurrentConflict = { suppressions++ },
                openSettings = { selectedProject -> openedProject = selectedProject },
            )
        Notification.fire(suppressionNotification, suppressionNotification.actions.last(), null)

        assertThat(suppressions).isEqualTo(1)
        assertThat(suppressionNotification.isExpired).isTrue()
        assertThat(BracketGuideSettings.getInstance().options).isEqualTo(settingsBeforeAction)
        assertThat(
            Triple(
                CodeInsightSettings.getInstance().HIGHLIGHT_BRACES,
                CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE,
                EditorSettingsExternalizable.getInstance().isIndentGuidesShown,
            ),
        ).isEqualTo(nativeBeforeAction)
    }

    fun testInFlightCaretAwayAndBackRevokesTheOriginalProof() {
        assertInFlightRevoked { notification ->
            val offset = myFixture.editor.caretModel.offset
            myFixture.editor.caretModel.moveToOffset(offset + 1)
            notification.caretMoved(myFixture.editor)
            myFixture.editor.caretModel.moveToOffset(offset)
            notification.caretMoved(myFixture.editor)
        }
    }

    fun testInFlightDocumentEditRevokesTheOriginalProof() {
        assertInFlightRevoked {
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, " ")
            }
        }
    }

    fun testInFlightHighlighterReplacementRevokesTheOriginalProof() {
        assertInFlightRevoked {
            WriteCommandAction.runWriteCommandAction(project) {
                val replacement = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                    project,
                    myFixture.file.virtualFile,
                )
                (myFixture.editor as EditorEx).setHighlighter(replacement)
            }
        }
    }

    fun testInFlightSettingsAwayAndBackRevokesTheOriginalProof() {
        assertInFlightRevoked { notification ->
            notification.settingsChanged(CONFLICT_PREFERENCES, SAFE_PREFERENCES)
            notification.settingsChanged(SAFE_PREFERENCES, CONFLICT_PREFERENCES)
        }
    }

    fun testDisposalCancelsAnInFlightProofWithoutWaitingForTheGate() {
        assertInFlightRevoked { it.dispose() }
    }

    fun testDisposedEditorCancelsAnInFlightProofWithoutPublication() {
        val scheduler = ManualScheduler()
        val gate = InspectionGate()
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("class Disposed {}"), project)
        var publications = 0
        val notification = NativeGuideConflictNotification(
            scope = testScope,
            preferences = { CONFLICT_PREFERENCES },
            captureConflict = { _, _, _ -> NativeConflictProbe { checkCanceled -> gate.inspect(checkCanceled) } },
            createNotification = { _, _ ->
                publications++
                testNotification()
            },
            schedule = scheduler::schedule,
        )
        try {
            notification.consider(editor, MULTILINE_GUIDE)
            scheduler.startNext()
            gate.awaitStarted()
            factory.releaseEditor(editor)
            awaitWorkers()
            assertThat(publications).isZero()
            assertThat(gate.release.count).isEqualTo(1)
        } finally {
            gate.release.countDown()
            if (!editor.isDisposed) factory.releaseEditor(editor)
        }
    }

    fun testCompletedProofRevokedDuringEdtAcceptanceCannotPublish() {
        val scheduler = ManualScheduler()
        var publications = 0
        lateinit var notification: NativeGuideConflictNotification
        notification = NativeGuideConflictNotification(
            scope = testScope,
            preferences = { CONFLICT_PREFERENCES },
            captureConflict = { _, _, _ ->
                object : NativeConflictProbe {
                    override suspend fun inspect(checkCanceled: () -> Unit): Boolean = true
                    override fun isCurrent(): Boolean {
                        assertThat(ApplicationManager.getApplication().isDispatchThread).isTrue()
                        notification.dispose()
                        return true
                    }
                }
            },
            createNotification = { _, _ ->
                publications++
                testNotification()
            },
            schedule = scheduler::schedule,
        )
        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        assertThat(publications).isZero()
    }

    fun testDriverMuteCancelsAnInFlightProofWithoutWaitingForTheGate() {
        assertInFlightRevoked { it.muteForDriverSession() }
    }

    private fun assertInFlightRevoked(mutate: (NativeGuideConflictNotification) -> Unit) {
        val scheduler = ManualScheduler()
        val gate = InspectionGate()
        var publications = 0
        val notification = NativeGuideConflictNotification(
            scope = testScope,
            preferences = { CONFLICT_PREFERENCES },
            captureConflict = { _, _, _ -> NativeConflictProbe { checkCanceled -> gate.inspect(checkCanceled) } },
            createNotification = { _, _ ->
                publications++
                testNotification()
            },
            schedule = scheduler::schedule,
        )
        try {
            notification.consider(myFixture.editor, MULTILINE_GUIDE)
            scheduler.startNext()
            gate.awaitStarted()
            mutate(notification)
            awaitWorkers()
            assertThat(gate.finished.count).isZero()
            assertThat(publications).isZero()
            assertThat(notification.state.suppressedForCurrentConflict).isFalse()
        } finally {
            gate.release.countDown()
        }
    }

    fun testLatestGuideCancelsTheSameEditorsOlderInFlightProof() {
        val scheduler = ManualScheduler()
        val gate = InspectionGate()
        val replacement = MULTILINE_GUIDE.copy(anchorLine = 2)
        var publications = 0
        val notification = NativeGuideConflictNotification(
            scope = testScope,
            preferences = { CONFLICT_PREFERENCES },
            captureConflict = { _, guide, _ ->
                NativeConflictProbe { checkCanceled ->
                    if (guide == MULTILINE_GUIDE) gate.inspect(checkCanceled) else true
                }
            },
            createNotification = { _, _ ->
                publications++
                testNotification()
            },
            schedule = scheduler::schedule,
        )
        try {
            notification.consider(myFixture.editor, MULTILINE_GUIDE)
            scheduler.startNext()
            gate.awaitStarted()
            notification.consider(myFixture.editor, replacement)
            scheduler.runNext()
            assertThat(publications).isEqualTo(1)
            assertThat(gate.finished.count).isZero()
            assertThat(gate.release.count).isEqualTo(1)
        } finally {
            gate.release.countDown()
        }
    }

    fun testSlowEditorDoesNotBlockAnotherEditorsPublication() {
        val scheduler = ManualScheduler()
        val gate = InspectionGate()
        val factory = EditorFactory.getInstance()
        val other = factory.createEditor(factory.createDocument("class Other {}"), project)
        var publications = 0
        val notification = NativeGuideConflictNotification(
            scope = testScope,
            preferences = { CONFLICT_PREFERENCES },
            captureConflict = { editor, _, _ ->
                NativeConflictProbe { checkCanceled ->
                    if (editor === myFixture.editor) gate.inspect(checkCanceled) else true
                }
            },
            createNotification = { _, _ ->
                publications++
                testNotification()
            },
            schedule = scheduler::schedule,
        )
        try {
            notification.consider(myFixture.editor, MULTILINE_GUIDE)
            scheduler.startNext()
            gate.awaitStarted()
            notification.consider(other, MULTILINE_GUIDE)
            scheduler.runNext()
            assertThat(publications).isEqualTo(1)
            assertThat(gate.finished.count).isZero()
            assertThat(gate.release.count).isEqualTo(1)
        } finally {
            gate.release.countDown()
            factory.releaseEditor(other)
        }
    }

    private class InspectionGate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        fun inspect(checkCanceled: () -> Unit): Boolean {
            check(!ApplicationManager.getApplication().isDispatchThread)
            started.countDown()
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) checkCanceled()
                checkCanceled()
                return true
            } finally {
                finished.countDown()
            }
        }

        fun awaitStarted() {
            PlatformTestUtil.waitWithEventsDispatching("Native conflict inspection did not start", {
                started.count == 0L
            }, 10)
        }
    }

    private companion object {
        val CONFLICT_PREFERENCES =
            BracketGuidePreferences(
                intelliJIntegration =
                IntelliJIntegrationPreferences(
                    manageNativeVisuals = false,
                    nativeHighlightMode = NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                ),
            )
        val SAFE_PREFERENCES = CONFLICT_PREFERENCES.copy(enabled = false)
        val MULTILINE_GUIDE =
            BracketGuide(
                pair =
                BracketPair(
                    openOffset = 15,
                    openTokenLength = 1,
                    closeOffset = 45,
                    closeTokenLength = 1,
                    depth = 0,
                    openLine = 0,
                    closeLine = 3,
                ),
                guideColumn = 0,
                anchorLine = 1,
            )

        fun testNotification(): Notification = Notification(
            "Bracket Pair Guides Test",
            "Native conflict",
            "Test notification",
            NotificationType.INFORMATION,
        )
    }

    private inner class ManualScheduler {
        private val tasks = ArrayDeque<() -> Unit>()

        val pendingCount: Int
            get() = tasks.size

        fun schedule(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun startNext() {
            tasks.removeFirst().invoke()
        }

        fun runNext() {
            startNext()
            awaitWorkers()
        }
    }
}
