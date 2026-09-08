package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat
import java.util.ArrayDeque

class NativeGuideConflictNotificationTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
        myFixture.configureByText(
            "Conflict.java",
            "class Conflict {\n    void run() {\n    }\n}",
        )
    }

    fun testQualifyingMultilineGuidePublishesOnceAndPersistsTheDecision() {
        val expectedPreferences = BracketGuidePreferences()
        val scheduler = ManualScheduler()
        var conflictChecks = 0
        var publications = 0
        val latestGuide = MULTILINE_GUIDE.copy(anchorLine = 2)
        val notification =
            NativeGuideConflictNotification(
                preferences = { expectedPreferences },
                isConflict = { editor, guide, preferences ->
                    assertThat(editor).isSameAs(myFixture.editor)
                    assertThat(guide).isEqualTo(latestGuide)
                    assertThat(preferences).isSameAs(expectedPreferences)
                    conflictChecks++
                    true
                },
                publish = { publishedProject ->
                    assertThat(publishedProject).isSameAs(project)
                    publications++
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        notification.consider(myFixture.editor, latestGuide)

        assertThat(scheduler.pendingCount).isEqualTo(1)
        assertThat(conflictChecks).isZero()
        assertThat(publications).isZero()
        assertThat(notification.state.published).isFalse()

        scheduler.runNext()

        assertThat(conflictChecks).isEqualTo(1)
        assertThat(publications).isEqualTo(1)
        assertThat(notification.state.published).isTrue()

        var restoredPublications = 0
        val restoredScheduler = ManualScheduler()
        val restored =
            NativeGuideConflictNotification(
                preferences = { expectedPreferences },
                isConflict = { _, _, _ -> true },
                publish = { restoredPublications++ },
                schedule = restoredScheduler::schedule,
            )
        val serialized = XmlSerializer.serialize(notification.state)
        restored.loadState(
            XmlSerializer.deserialize(
                serialized,
                NativeGuideConflictNotification.NotificationState::class.java,
            ),
        )

        restored.consider(myFixture.editor, MULTILINE_GUIDE)

        assertThat(restored.state.published).isTrue()
        assertThat(restoredPublications).isZero()
        assertThat(restoredScheduler.pendingCount).isZero()
    }

    fun testConflictIsNotLostWhenAnotherEditorReportsNonConflictInTheSameTick() {
        val scheduler = ManualScheduler()
        val editorFactory = EditorFactory.getInstance()
        val nonConflictEditor =
            editorFactory.createEditor(
                editorFactory.createDocument("class NonConflict {}"),
                project,
            )
        val inspectedEditors = mutableListOf<Editor>()
        var publications = 0
        try {
            val notification =
                NativeGuideConflictNotification(
                    preferences = { BracketGuidePreferences() },
                    isConflict = { editor, _, _ ->
                        inspectedEditors += editor
                        editor === myFixture.editor
                    },
                    publish = { publications++ },
                    schedule = scheduler::schedule,
                )

            notification.consider(myFixture.editor, MULTILINE_GUIDE)
            notification.consider(nonConflictEditor, MULTILINE_GUIDE)

            assertThat(scheduler.pendingCount).isEqualTo(1)
            scheduler.runNext()

            assertThat(inspectedEditors).contains(myFixture.editor)
            assertThat(publications).isEqualTo(1)
            assertThat(notification.state.published).isTrue()
        } finally {
            editorFactory.releaseEditor(nonConflictEditor)
        }
    }

    fun testFailedPublicationIsNotMarkedAndTheNextPresentationRetries() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                preferences = { BracketGuidePreferences() },
                isConflict = { _, _, _ -> true },
                publish = {
                    publications++
                    if (publications == 1) error("synthetic publish failure")
                },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()
        assertThat(notification.state.published).isFalse()

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        assertThat(publications).isEqualTo(2)
        assertThat(notification.state.published).isTrue()
    }

    fun testNonConflictDoesNotPublishOrConsumeTheOneShot() {
        val scheduler = ManualScheduler()
        var publications = 0
        val notification =
            NativeGuideConflictNotification(
                preferences = { BracketGuidePreferences() },
                isConflict = { _, _, _ -> false },
                publish = { publications++ },
                schedule = scheduler::schedule,
            )

        notification.consider(myFixture.editor, MULTILINE_GUIDE)
        scheduler.runNext()

        assertThat(publications).isZero()
        assertThat(notification.state.published).isFalse()
    }

    fun testCaretOrDocumentChangeDropsAStaleCandidateBeforeDetection() {
        val scheduler = ManualScheduler()
        var conflictChecks = 0
        val notification =
            NativeGuideConflictNotification(
                preferences = { BracketGuidePreferences() },
                isConflict = { _, _, _ ->
                    conflictChecks++
                    true
                },
                publish = { error("A stale candidate must not publish") },
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
        assertThat(notification.state.published).isFalse()
    }

    fun testDisposedEditorDropsAQueuedCandidateBeforeDetection() {
        val scheduler = ManualScheduler()
        var conflictChecks = 0
        val notification =
            NativeGuideConflictNotification(
                preferences = { BracketGuidePreferences() },
                isConflict = { _, _, _ ->
                    conflictChecks++
                    true
                },
                publish = { error("A disposed editor candidate must not publish") },
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
        assertThat(notification.state.published).isFalse()
    }

    fun testBalloonIsInformationalAndOffersOnlyReviewSettings() {
        var openedProject: Project? = null
        val settingsBeforeAction = BracketGuideSettings.getInstance().options
        val nativeBeforeAction =
            Triple(
                CodeInsightSettings.getInstance().HIGHLIGHT_BRACES,
                CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE,
                EditorSettingsExternalizable.getInstance().isIndentGuidesShown,
            )
        val notification =
            NativeGuideConflictBalloon.create(project) { selectedProject ->
                openedProject = selectedProject
            }

        assertThat(notification.type).isEqualTo(NotificationType.INFORMATION)
        assertThat(notification.title).isEqualTo(NativeGuideConflictBalloon.TITLE)
        assertThat(notification.content).isEqualTo(NativeGuideConflictBalloon.CONTENT)
        assertThat(notification.actions).hasSize(1)
        assertThat(notification.actions.single().templateText)
            .isEqualTo(NativeGuideConflictBalloon.ACTION_TEXT)

        Notification.fire(notification, notification.actions.single(), null)

        assertThat(openedProject).isSameAs(project)
        assertThat(BracketGuideSettings.getInstance().options).isEqualTo(settingsBeforeAction)
        assertThat(
            Triple(
                CodeInsightSettings.getInstance().HIGHLIGHT_BRACES,
                CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE,
                EditorSettingsExternalizable.getInstance().isIndentGuidesShown,
            ),
        ).isEqualTo(nativeBeforeAction)
    }

    private companion object {
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
    }

    private class ManualScheduler {
        private val tasks = ArrayDeque<() -> Unit>()

        val pendingCount: Int
            get() = tasks.size

        fun schedule(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun runNext() {
            tasks.removeFirst().invoke()
        }
    }
}
