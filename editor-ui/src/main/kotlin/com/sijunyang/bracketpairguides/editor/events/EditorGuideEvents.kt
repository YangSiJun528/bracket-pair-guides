package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.sijunyang.bracketpairguides.editor.EditorActivitySource
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.presentation.DocumentChange
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.jetbrains.annotations.TestOnly
import java.awt.KeyboardFocusManager
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.beans.PropertyChangeListener
import java.util.IdentityHashMap

/** Routes platform editor events to the state owned by each editor session. */
@Service(Service.Level.APP)
class EditorGuideEvents :
    CaretListener,
    DocumentListener,
    EditorFactoryListener,
    EditorColorsListener,
    VisibleAreaListener,
    Disposable {
    private val activityObservers = IdentityHashMap<Editor, HierarchyListener>()
    private val propertyObservers = IdentityHashMap<EditorEx, PropertyChangeListener>()
    private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private var focusRefreshPending = false
    private val focusListener = PropertyChangeListener {
        onEdt {
            if (activityObservers.isNotEmpty() && !focusRefreshPending) {
                focusRefreshPending = true
                // Presentation must follow focus even while Settings is modal.
                ApplicationManager.getApplication().invokeLater({
                    focusRefreshPending = false
                    if (EditorEffectGuard.allowsEffects()) {
                        activityObservers.keys.toList().forEach(::refreshActivity)
                    }
                }, ModalityState.any())
            }
        }
    }

    private val visibleRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val visibleRefreshBatch =
        IdentityEventBatch<Editor>(
            schedule = { refresh ->
                visibleRefreshAlarm.addRequest(refresh, VISIBLE_REFRESH_DELAY_MILLIS)
            },
            consume = { editor ->
                if (!editor.isDisposed) EditorGuideSessions.get(editor)?.visibleAreaChanged()
            },
        )
    private val stickyMarkupByEditor = IdentityHashMap<Editor, StickyMarkupObservation>()
    private val stickyMarkupObservations =
        IdentityHashMap<MarkupModelEx, StickyMarkupObservation>()

    init {
        focusManager.addPropertyChangeListener("focusOwner", focusListener)
        val editorFactory = EditorFactory.getInstance()
        editorFactory.eventMulticaster.addCaretListener(this, this)
        editorFactory.eventMulticaster.addDocumentListener(this, this)
        editorFactory.eventMulticaster.addVisibleAreaListener(this, this)
        editorFactory.addEditorFactoryListener(this, this)
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(EditorColorsManager.TOPIC, this)
        EditorSettingsExternalizable.getInstance().addPropertyChangeListener(
            {
                requestVisibleRefreshes(EditorFactory.getInstance().allEditors.toList())
            },
            this,
        )
    }

    override fun caretPositionChanged(event: CaretEvent): Unit = onEdt(event.editor) {
        if (event.caret?.let { it !== event.editor.caretModel.primaryCaret } == true) {
            return@onEdt
        }
        primaryCaretChanged(event.editor)
    }

    override fun caretAdded(event: CaretEvent): Unit = caretPositionChanged(event)

    // The removed caret is no longer primary when this callback runs, so the
    // post-removal primary selection must be refreshed without the event filter.
    override fun caretRemoved(event: CaretEvent): Unit = onEdt(event.editor) {
        primaryCaretChanged(event.editor)
    }

    /**
     * HARD EDT CONTRACT: every visible tracked pair must be refreshed or removed
     * before a normal platform document callback returns. Never batch, debounce,
     * or enqueue the EDT path. Background analysis may discover a new pair later,
     * but it must never be needed to repair geometry that this edit made stale.
     * A defensive off-EDT handoff remains asynchronous because blocking a host
     * write path with invokeAndWait can deadlock the IDE.
     */
    override fun documentChanged(event: DocumentEvent) {
        // Preview copies have no guides to update, and their background edits
        // must not schedule EDT work. Check the preview context on this thread.
        if (!EditorEffectGuard.allowsEffects()) return

        val change =
            DocumentChange(
                offset = event.offset,
                oldLength = event.oldLength,
                newLength = event.newLength,
            )
        val editors = EditorFactory.getInstance().getEditors(event.document).toList()
        if (editors.none { EditorGuideSessions.get(it) != null }) return
        // Valid document writes for the supported platform run on EDT. onEdt
        // therefore executes ordinary typing synchronously. Its off-EDT branch
        // must stay asynchronous: invokeAndWait can deadlock a host write path.
        onEdt {
            for (editor in editors) {
                if (!editor.isDisposed) {
                    EditorGuideSessions.get(editor)?.documentChanged(change)
                }
            }
        }
    }

    override fun visibleAreaChanged(event: VisibleAreaEvent): Unit = onEdt(event.editor) {
        if (EditorGuideSessions.get(event.editor) != null) {
            visibleRefreshBatch.request(event.editor)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        activityObservers.remove(event.editor)?.let(event.editor.contentComponent::removeHierarchyListener)
        (event.editor as? EditorEx)?.let { editor ->
            propertyObservers.remove(editor)?.let(editor::removePropertyChangeListener)
        }
        visibleRefreshBatch.remove(event.editor)
        stopObservingStickyLineModel(event.editor)
        EditorGuideSessions.dispose(event.editor)
    }

    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        for (editor in EditorFactory.getInstance().allEditors) {
            onEdt(editor) {
                EditorGuideSessions.get(editor)?.updateOptions(
                    BracketGuideSettings.getInstance().options,
                    refreshColors = true,
                )
            }
        }
    }

    override fun dispose() {
        focusManager.removePropertyChangeListener("focusOwner", focusListener)
        activityObservers.forEach { (editor, listener) ->
            editor.contentComponent.removeHierarchyListener(listener)
        }
        activityObservers.clear()
        propertyObservers.forEach { (editor, listener) -> editor.removePropertyChangeListener(listener) }
        propertyObservers.clear()
        visibleRefreshBatch.clear()
        stickyMarkupByEditor.clear()
        val observations = stickyMarkupObservations.values.toList()
        stickyMarkupObservations.clear()
        observations.forEach { observation ->
            Disposer.dispose(observation.parentDisposable)
        }
        for (editor in EditorFactory.getInstance().allEditors) {
            EditorGuideSessions.dispose(editor)
        }
    }

    private fun observeActivity(editor: Editor) {
        if (activityObservers.containsKey(editor)) return
        val listener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                onEdt(editor) { refreshActivity(editor) }
            }
        }
        activityObservers[editor] = listener
        editor.contentComponent.addHierarchyListener(listener)
        if (editor is EditorEx) {
            val propertyListener = PropertyChangeListener { event ->
                when (event.propertyName) {
                    EditorEx.PROP_ONE_LINE_MODE -> onEdt(editor) {
                        refreshActivity(editor)
                        DaemonRefresh.request()
                    }

                    EditorEx.PROP_HIGHLIGHTER -> onEdt(editor) {
                        EditorGuideSessions.get(editor)?.let { session ->
                            session.visibleAreaChanged()
                            session.requestAnalysis()
                        }
                    }
                }
            }
            propertyObservers[editor] = propertyListener
            editor.addPropertyChangeListener(propertyListener)
        }
    }

    private fun refreshActivity(editor: Editor) {
        if (editor.isDisposed) return
        EditorGuideSessions.get(editor)?.updateSurface(
            EditorSurfaceClassifier.capabilities(editor),
            EditorActivitySource.capture(editor),
        )
    }

    private fun observeStickyLineModel(editor: Editor) {
        val markup = StickyLineSourceRanges.documentMarkupModel(editor) ?: return
        val current = stickyMarkupByEditor[editor]
        if (current?.markup === markup) return
        if (current != null) stopObservingStickyLineModel(editor)

        val observation = stickyMarkupObservations[markup] ?: createObservation(markup)
        observation.editorCount++
        stickyMarkupByEditor[editor] = observation
    }

    private fun createObservation(markup: MarkupModelEx): StickyMarkupObservation {
        val parentDisposable = Disposer.newDisposable("Bracket Pair Guides sticky-line observer")
        markup.addMarkupModelListener(
            parentDisposable,
            object : MarkupModelListener {
                override fun afterAdded(highlighter: RangeHighlighterEx) {
                    if (StickyLineSourceRanges.isStickyLineMarker(highlighter)) {
                        stickyLinesChanged(markup.document)
                    }
                }

                override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                    if (StickyLineSourceRanges.isStickyLineMarker(highlighter)) {
                        stickyLinesChanged(markup.document)
                    }
                }

                override fun attributesChanged(
                    highlighter: RangeHighlighterEx,
                    renderersChanged: Boolean,
                    fontStyleOrColorChanged: Boolean,
                ) {
                    if (StickyLineSourceRanges.isStickyLineMarker(highlighter)) {
                        stickyLinesChanged(markup.document)
                    }
                }
            },
        )
        return StickyMarkupObservation(markup, parentDisposable).also { observation ->
            stickyMarkupObservations[markup] = observation
        }
    }

    private fun stopObservingStickyLineModel(editor: Editor) {
        val observation = stickyMarkupByEditor.remove(editor) ?: return
        observation.editorCount--
        if (observation.editorCount > 0) return
        stickyMarkupObservations.remove(observation.markup)
        Disposer.dispose(observation.parentDisposable)
    }

    private fun stickyLinesChanged(document: Document) {
        onEdt {
            requestVisibleRefreshes(
                EditorFactory.getInstance().getEditors(document).toList(),
            )
        }
    }

    private fun requestVisibleRefreshes(editors: List<Editor>) {
        onEdt {
            for (editor in editors) {
                if (!editor.isDisposed && EditorGuideSessions.get(editor) != null) {
                    visibleRefreshBatch.request(editor)
                }
            }
        }
    }

    private fun primaryCaretChanged(editor: Editor) {
        val session = EditorGuideSessions.get(editor) ?: return
        if (session.drawsGuides) BracketGuideSettingsController.getInstance().reconcileNativeSettings()
        session.caretMoved()
        if (session.hasCappedTokenDecorations) {
            visibleRefreshBatch.request(editor)
        }
    }

    private fun onEdt(editor: Editor, action: () -> Unit) {
        if (EditorGuideSessions.get(editor) == null) return
        onEdt {
            if (!editor.isDisposed) action()
        }
    }

    private fun onEdt(action: () -> Unit) {
        if (!EditorEffectGuard.allowsEffects()) return
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            action()
        } else {
            application.invokeLater { if (EditorEffectGuard.allowsEffects()) action() }
        }
    }

    companion object {
        private const val VISIBLE_REFRESH_DELAY_MILLIS = 16

        fun ensureInitialized(editor: Editor? = null, observeStickyLines: Boolean? = null) {
            if (!EditorEffectGuard.allowsEffects()) return
            val events =
                ApplicationManager
                    .getApplication()
                    .getService(EditorGuideEvents::class.java)
            if (editor != null) {
                events.observeActivity(editor)
                if (observeStickyLines ?: EditorSurfaceClassifier.capabilities(editor).activePair) {
                    events.observeStickyLineModel(editor)
                }
            }
        }

        @TestOnly
        internal fun isObservingStickyLineModel(editor: Editor): Boolean {
            val events =
                ApplicationManager
                    .getApplication()
                    .getService(EditorGuideEvents::class.java)
            return events.stickyMarkupByEditor.containsKey(editor)
        }
    }

    private class StickyMarkupObservation(
        val markup: MarkupModelEx,
        val parentDisposable: Disposable,
        var editorCount: Int = 0,
    )
}
