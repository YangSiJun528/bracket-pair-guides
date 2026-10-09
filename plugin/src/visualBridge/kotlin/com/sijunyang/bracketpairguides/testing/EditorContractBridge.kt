package com.sijunyang.bracketpairguides.testing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.ui.LafManager
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.ui.BalloonImpl
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities
import com.intellij.openapi.wm.WindowManager
import com.sijunyang.bracketpairguides.plugin.GuidePlugin
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.ui.settings.GuideUiSettings
import java.util.concurrent.atomic.AtomicReference

/** Test-only primitive transport; all preference changes use the production settings command. */
@Suppress("unused")
object EditorContractBridge {
    private var observingNotifications = false
    private var advisoryCount = 0
    private var advisory: Notification? = null
    private var focusRequestSequence = 0L
    private var focusRequestStatus = "none"
    private var requestFocusInWindowResult: Boolean? = null
    private var tabOriginal: Editor? = null
    private var tabOther: Editor? = null
    private var tabOriginalStamp = 0L
    private var tabOtherStamp = 0L
    private var tabOriginalTokens = emptyList<String>()
    private var tabOtherTokens = emptyList<String>()
    private var lastTabObservation = "not-started"
    private var lastCaretCycleObservation = "not-started"

    @JvmStatic
    @Suppress("UnstableApiUsage")
    fun configure(): String = edt {
        if (!observingNotifications) {
            val project = checkNotNull(editor().project)
            project.messageBus.connect(project).subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Bracket Pair Guides Native Visual Conflict") {
                        advisoryCount++
                        advisory = notification
                    }
                }
            })
            observingNotifications = true
        }
        GuideUiSettings.apply(BracketGuidePreferences(enabled = false))
        val appearance = LafManager.getInstance()
        appearance.autodetect = false
        appearance.currentUIThemeLookAndFeel = checkNotNull(appearance.installedThemes.firstOrNull { it.name == "Darcula" })
        appearance.updateUI()
        EditorColorsManager.getInstance().globalScheme.apply {
            editorFontName = "JetBrains Mono"
            editorFontSize = 14
            lineSpacing = 1.0f
        }
        CodeVisionSettings.getInstance().codeVisionEnabled = false
        EditorSettingsExternalizable.getInstance().apply {
            isShowIntentionBulb = false
            isIndentGuidesShown = true
        }
        CodeInsightSettings.getInstance().apply { HIGHLIGHT_BRACES = true; HIGHLIGHT_SCOPE = true }
        checkNotNull(WindowManager.getInstance().findVisibleFrame()).apply {
            setBounds(100, 100, 1280, 900)
            isAlwaysOnTop = true
        }
        nativeState()
    }

    @JvmStatic
    fun scenario(name: String): String = edt {
        GuideUiSettings.apply(BracketGuidePreferences(enabled = false))
        CodeInsightSettings.getInstance().apply { HIGHLIGHT_BRACES = true; HIGHLIGHT_SCOPE = true }
        EditorSettingsExternalizable.getInstance().isIndentGuidesShown = true
        val editor = editor()
        editor.caretModel.moveToLogicalPosition(if (name.startsWith("native-")) LogicalPosition(2, 20) else LogicalPosition(3, 15))
        (editor as EditorEx).setCaretEnabled(false)
        editor.setCaretVisible(false)
        editor.scrollingModel.scrollVertically(0)
        editor.scrollingModel.scrollHorizontally(0)
        editor.settings.isCaretRowShown = false
        editor.settings.isLineNumbersShown = false
        editor.contentComponent.requestFocusInWindow()
        val all = BracketGuidePreferences(showActivePairBorder = true, showActivePairBackground = true)
        val preferences = when (name) {
            "horizontal-only" -> all.copy(showVerticalGuide = false, showActivePairBorder = false, showActivePairBackground = false)
            "vertical-only" -> all.copy(showHorizontalGuides = false, showActivePairBorder = false, showActivePairBackground = false)
            "pair-border-only" -> all.copy(showActiveGuide = false, showActivePairBackground = false)
            "pair-background-only" -> all.copy(showActiveGuide = false, showActivePairBorder = false)
            "all-components", "native-highlight-suppressed" -> all
            "bracket-colorization-off" -> all.copy(colorBracketTokens = false)
            "plugin-disabled" -> all.copy(enabled = false)
            "native-visuals-unmanaged" -> all.copy(intelliJIntegration = IntelliJIntegrationPreferences(manageNativeVisuals = false))
            "default-palette" -> all.copy(guideLineWidth = 3, pairBackgroundOpacityPercent = 45)
            "custom-palette" -> all.copy(
                guideLineWidth = 3, pairBackgroundOpacityPercent = 45,
                useIndependentComponentColors = true,
                levelBaseColors = listOf(0xFF5555, 0x55FF55, 0x5599FF, 0xFFFF55, 0xFF55FF, 0x55FFFF),
                guideLineColors = List(6) { 0x55FFFF },
                pairBorderColors = List(6) { 0xFF55FF },
                pairBackgroundColors = List(6) { 0xFFFF55 },
            )
            else -> error("Unknown visual contract: $name")
        }
        val plugin = ApplicationManager.getApplication().getService(GuidePlugin::class.java)
        plugin.applyPreferences(preferences)
        plugin.request(editor)
        stateOnEdt(editor)
    }

    /** Opens the registered, real configurable; Driver alone activates its controls and Apply. */
    @JvmStatic
    fun openSettings() {
        ApplicationManager.getApplication().invokeLater({
            ShowSettingsUtil.getInstance().showSettingsDialog(editor().project, "Bracket Pair Guides")
        }, ModalityState.nonModal())
    }

    @JvmStatic
    fun settingsState(): String = readEdt {
        "${GuideUiSettings.current().intelliJIntegration.manageNativeVisuals}:${nativeState()}"
    }

    /** Public notification-bus and actual balloon lifecycle observations, never proof-state getters. */
    @JvmStatic
    fun advisoryState(): String = readEdt {
        val balloon = advisory?.balloon
        // The pinned Driver SDK exposes visibility on BalloonImpl, not the Balloon interface.
        val componentShowing = (balloon as? BalloonImpl)?.component?.isShowing == true
        val visible = (balloon as? BalloonImpl)?.isVisible == true && componentShowing
        "$advisoryCount:$visible:${advisory?.isExpired == true}:" +
            "${balloon?.wasFadedIn() == true}:${balloon?.isDisposed == true}:${balloon != null}:" +
            "$componentShowing:${balloon?.javaClass?.name}"
    }

    @JvmStatic
    fun dismissAdvisory() = edt { advisory?.hideBalloon(); Unit }

    /** Actual cached pair transitions, observed before this non-modal EDT turn can return. */
    @JvmStatic
    fun caretCycle(): String = edt {
        val editor = editor()
        val manager = FileEditorManager.getInstance(checkNotNull(editor.project))
        val stamp = editor.document.modificationStamp
        val tokens = tokenRanges(editor)
        fun guides() = editor.markupModel.allHighlighters.filter {
            it.isValid && it.customRenderer?.javaClass?.name?.startsWith("com.sijunyang.bracketpairguides.") == true
        }
        fun endpoints() = editor.markupModel.allHighlighters.filter {
            val attributes = it.getTextAttributes(editor.colorsScheme)
            it.isValid && it.textAttributesKey == null && it.layer == HighlighterLayer.ELEMENT_UNDER_CARET &&
                it.endOffset - it.startOffset == 1 &&
                (attributes?.effectType == EffectType.BOXED || attributes?.backgroundColor != null)
        }.map { "${it.startOffset}..${it.endOffset}" }.sorted()
        fun brace(line: Int, character: Char): Int {
            val document = editor.document
            val start = document.getLineStartOffset(line)
            val text = document.charsSequence.subSequence(start, document.getLineEndOffset(line))
            val column = text.indexOf(character)
            check(column >= 0) { "Fixture line $line lacks expected $character" }
            return start + column
        }
        val inner = listOf(brace(2, '{'), brace(4, '}')).map { "$it..${it + 1}" }.sorted()
        val outer = listOf(brace(1, '{'), brace(5, '}')).map { "$it..${it + 1}" }.sorted()
        val sameLine = listOf(brace(6, '{'), brace(6, '}')).map { "$it..${it + 1}" }.sorted()
        val observations = mutableListOf<String>()
        fun observe(phase: String): String =
            "phase=$phase;onEdt=${ApplicationManager.getApplication().isDispatchThread};" +
                "editorIdentity=${System.identityHashCode(editor)};selected=${manager.selectedTextEditor === editor};" +
                "stamp=${editor.document.modificationStamp};showing=${editor.contentComponent.isShowing};" +
                "focused=${editor.contentComponent.isFocusOwner};caret=${editor.caretModel.offset};" +
                "guideIdentities=${guides().map(System::identityHashCode)};endpoints=${endpoints()};" +
                "tokens=${tokenRanges(editor)};nativeBraceCount=${nativeBraceCount()};state=${stateOnEdt(editor)}"
        observations += observe("warm-before")
        lastCaretCycleObservation = observations.joinToString("\n")
        check(manager.selectedTextEditor === editor && editor.contentComponent.isShowing && editor.contentComponent.isFocusOwner)
        check(tokens.isNotEmpty()) { "Caret cycle requires a warm token window" }
        val originalGuide = guides().single()
        check(endpoints() == inner) { "Caret cycle must start at the warmed inner pair" }
        val steps = listOf(
            Triple("inner-A", LogicalPosition(3, 15), inner),
            Triple("outer-B", LogicalPosition(5, 4), outer),
            Triple("inner-A-return", LogicalPosition(3, 15), inner),
            Triple("same-line-C", LogicalPosition(6, 20), sameLine),
            Triple("inner-A-final", LogicalPosition(3, 15), inner),
        )
        for ((phase, position, expectedEndpoints) in steps) {
            editor.caretModel.moveToLogicalPosition(position)
            // No explicit analysis request, event pump or analysis wait precedes this snapshot.
            observations += observe(phase)
            lastCaretCycleObservation = observations.joinToString("\n")
            check(manager.selectedTextEditor === editor && editor.contentComponent.isShowing && editor.contentComponent.isFocusOwner)
            check(editor.document.modificationStamp == stamp)
            check(tokenRanges(editor) == tokens) { "Caret change altered token markup: $lastCaretCycleObservation" }
            check(originalGuide.isValid && guides().singleOrNull() === originalGuide) {
                "Cached pair change cleared the full-document guide highlighter: $lastCaretCycleObservation"
            }
            check(endpoints() == expectedEndpoints) {
                "Caret move returned without the new pair endpoints: $lastCaretCycleObservation"
            }
        }
        lastCaretCycleObservation
    }

    @JvmStatic
    fun caretCycleDiagnostics(): String = readEdt { lastCaretCycleObservation }

    @JvmStatic
    fun focusEditor(focused: Boolean): String = edt {
        val editor = editor()
        val target = if (focused) editor.contentComponent else
            checkNotNull(WindowManager.getInstance().findVisibleFrame()).rootPane.apply { isFocusable = true }
        requestFocusInWindowResult = target.requestFocusInWindow()
        val sequence = ++focusRequestSequence
        focusRequestStatus = "waiting-for-focus-settlement"
        val manager = IdeFocusManager.getInstance(editor.project)
        manager.doWhenFocusSettlesDown({
            if (editor.isDisposed) {
                if (sequence == focusRequestSequence) focusRequestStatus = "editor-disposed"
            } else {
                if (sequence == focusRequestSequence) focusRequestStatus = "requested"
                manager.requestFocus(target, true)
                    .doWhenDone { if (sequence == focusRequestSequence) focusRequestStatus = "done" }
                    .doWhenRejected(Runnable { if (sequence == focusRequestSequence) focusRequestStatus = "rejected" })
            }
        }, ModalityState.nonModal())
        stateOnEdt(editor)
    }

    /** Public SDK focus/window facts, with no presentation or runtime implementation lookup. */
    @JvmStatic
    fun focusDiagnostics(): String = readEdt {
        val editor = editor()
        val content = editor.contentComponent
        val keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val owner = keyboard.focusOwner
        val manager = IdeFocusManager.getInstance(editor.project)
        "requestSequence=$focusRequestSequence\nrequestFocusInWindow=$requestFocusInWindowResult\n" +
            "requestStatus=$focusRequestStatus\nfocusOwnerClass=${owner?.javaClass?.name}\n" +
            "ownerDescendsFromEditor=${owner != null && SwingUtilities.isDescendingFrom(owner, content)}\n" +
            "editorHasFocus=${content.hasFocus()}\neditorShowing=${content.isShowing}\n" +
            "selectedEditor=${editor.project?.let { FileEditorManager.getInstance(it).selectedTextEditor === editor }}\n" +
            "caretOffset=${editor.caretModel.offset}\nactiveWindowClass=${keyboard.activeWindow?.javaClass?.name}\n" +
            "focusManagerOwnerClass=${manager.focusOwner?.javaClass?.name}\nmodality=${ModalityState.current()}"
    }

    @JvmStatic
    fun showEditor(visible: Boolean): String = edt {
        editor().component.isVisible = visible
        stateOnEdt(editor())
    }

    /** Selects a real second file; normal editor events alone warm its analysis. */
    @JvmStatic
    fun prepareOtherTab(): String = edt {
        val original = editor()
        check(original.contentComponent.isShowing)
        val project = checkNotNull(original.project)
        val manager = FileEditorManager.getInstance(project)
        check(manager.selectedTextEditor === original)
        tabOriginal = original
        tabOriginalStamp = original.document.modificationStamp
        tabOriginalTokens = tokenRanges(original)
        check(tabOriginalTokens.isNotEmpty()) { "Original tab must be warm before switching" }
        val file = checkNotNull(FileDocumentManager.getInstance().getFile(original.document))
        val otherFile = checkNotNull(file.parent.findChild("TabContract.java"))
        manager.openFile(otherFile, true)
        val other = checkNotNull(manager.selectedTextEditor)
        tabOther = other
        check(FileDocumentManager.getInstance().getFile(other.document) == otherFile)
        (other as EditorEx).setCaretEnabled(false)
        other.setCaretVisible(false)
        other.settings.isCaretRowShown = false
        other.settings.isLineNumbersShown = false
        other.caretModel.moveToLogicalPosition(LogicalPosition(3, 15))
        lastTabObservation = tabObservation("first-other-selection")
        check(other !== original && other.contentComponent.isShowing)
        checkHiddenTab(original)
        lastTabObservation
    }

    @JvmStatic
    fun otherTabReady(): Boolean = readEdt {
        val other = tabOther ?: return@readEdt false
        !other.isDisposed && other.contentComponent.isShowing &&
            other.project?.let { FileEditorManager.getInstance(it).selectedTextEditor === other } == true &&
            tokenRanges(other).isNotEmpty()
    }

    /** The first return also records the warmed second tab's public markup contract. */
    @JvmStatic
    fun returnToOriginalTab(): String = edt {
        val original = checkNotNull(tabOriginal)
        val other = checkNotNull(tabOther)
        val manager = FileEditorManager.getInstance(checkNotNull(original.project))
        check(manager.selectedTextEditor === other && other.contentComponent.isShowing)
        if (tabOtherTokens.isEmpty()) {
            tabOtherStamp = other.document.modificationStamp
            tabOtherTokens = tokenRanges(other)
            check(tabOtherTokens.isNotEmpty()) { "Second tab must be warm before returning" }
        }
        checkHiddenTab(original)
        manager.openFile(checkNotNull(FileDocumentManager.getInstance().getFile(original.document)), true)
        // No event pump, analysis wait, or explicit plugin request may precede these observations.
        lastTabObservation = tabObservation("original-return-inside-selection-turn")
        check(manager.selectedTextEditor === original && original.contentComponent.isShowing)
        check(original.document.modificationStamp == tabOriginalStamp)
        check(tokenRanges(original) == tabOriginalTokens) {
            "Original tab lost its valid token markup at synchronous return: $lastTabObservation"
        }
        checkHiddenTab(other)
        lastTabObservation
    }

    @JvmStatic
    fun switchToOtherTab(): String = edt {
        val original = checkNotNull(tabOriginal)
        val other = checkNotNull(tabOther)
        val manager = FileEditorManager.getInstance(checkNotNull(original.project))
        check(manager.selectedTextEditor === original && original.contentComponent.isShowing)
        manager.openFile(checkNotNull(FileDocumentManager.getInstance().getFile(other.document)), true)
        lastTabObservation = tabObservation("other-return-inside-selection-turn")
        check(manager.selectedTextEditor === other && other.contentComponent.isShowing)
        check(other.document.modificationStamp == tabOtherStamp)
        check(tokenRanges(other) == tabOtherTokens) {
            "Second tab lost its valid token markup at synchronous return: $lastTabObservation"
        }
        checkHiddenTab(original)
        lastTabObservation
    }

    @JvmStatic
    fun tabSwitchDiagnostics(): String = readEdt {
        "$lastTabObservation\ncurrent:\n${tabObservation("read-only")}"
    }

    @JvmStatic
    fun closeOtherTab(): String = edt {
        val original = checkNotNull(tabOriginal)
        val other = checkNotNull(tabOther)
        val manager = FileEditorManager.getInstance(checkNotNull(original.project))
        check(manager.selectedTextEditor === original)
        manager.closeFile(checkNotNull(FileDocumentManager.getInstance().getFile(other.document)))
        lastTabObservation = tabObservation("other-closed")
        check(manager.selectedTextEditor === original && original.contentComponent.isShowing)
        check(tokenRanges(original) == tabOriginalTokens)
        tabOther = null
        lastTabObservation
    }

    private fun tokenRanges(editor: Editor): List<String> = editor.markupModel.allHighlighters
        .filter { it.isValid && it.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true }
        .map { "${it.startOffset}..${it.endOffset}:${it.textAttributesKey?.externalName}" }
        .sorted()

    private fun checkHiddenTab(editor: Editor) {
        check(!editor.contentComponent.isShowing) { "Unselected tab remained showing" }
        check(editor.markupModel.allHighlighters.none {
            it.isValid && (it.customRenderer?.javaClass?.name?.startsWith("com.sijunyang.bracketpairguides.") == true ||
                it.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true ||
                it.layer == HighlighterLayer.ELEMENT_UNDER_CARET &&
                it.endOffset - it.startOffset == 1 &&
                (it.getTextAttributes(editor.colorsScheme)?.effectType == EffectType.BOXED ||
                    it.getTextAttributes(editor.colorsScheme)?.backgroundColor != null))
        }) { "Hidden tab retained plugin markup: $lastTabObservation" }
    }

    private fun tabObservation(phase: String): String {
        fun describe(editor: Editor?): String {
            if (editor == null) return "absent"
            if (editor.isDisposed) return "identity=${System.identityHashCode(editor)};disposed=true"
            val selected = editor.project?.let { FileEditorManager.getInstance(it).selectedTextEditor === editor }
            return "identity=${System.identityHashCode(editor)};disposed=${editor.isDisposed};selected=$selected;" +
                "showing=${editor.contentComponent.isShowing};focused=${editor.contentComponent.isFocusOwner};" +
                "stamp=${editor.document.modificationStamp};tokens=${tokenRanges(editor)};state=${stateOnEdt(editor)}"
        }
        return "phase=$phase;onEdt=${ApplicationManager.getApplication().isDispatchThread}\n" +
            "original=${describe(tabOriginal)}\nother=${describe(tabOther)}"
    }

    @JvmStatic
    fun disable(): String = edt {
        GuideUiSettings.apply(GuideUiSettings.current().copy(enabled = false))
        nativeState()
    }

    @JvmStatic
    fun insertIndent(): Long = edt {
        val editor = editor()
        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.insertString(editor.document.getLineStartOffset(4), "  ")
            check(editor.markupModel.allHighlighters.none {
                it.customRenderer?.javaClass?.name?.startsWith("com.sijunyang.bracketpairguides.") == true
            }) { "Affected old guide remained inside the edit callback" }
        }
        editor.document.modificationStamp
    }

    /** Actual platform brace decorations are identified by the keys used by its SDK handler. */
    @JvmStatic
    fun nativeBraceCount(): Int = readEdt {
        editor().markupModel.allHighlighters.count { highlighter ->
            highlighter.isValid && (highlighter.textAttributesKey === CodeInsightColors.MATCHED_BRACE_ATTRIBUTES ||
                highlighter.textAttributesKey === CodeInsightColors.UNMATCHED_BRACE_ATTRIBUTES)
        }
    }

    @JvmStatic
    fun markupDiagnostics(): String = readEdt {
        val editor = editor()
        val all = editor.markupModel.allHighlighters
        "highlighters=${all.size},reported=${minOf(all.size, 256)}\n" +
            all.take(256).joinToString("\n") { highlighter ->
                val attributes = highlighter.getTextAttributes(editor.colorsScheme)
                "valid=${highlighter.isValid},range=${highlighter.startOffset}..${highlighter.endOffset}," +
                    "layer=${highlighter.layer},key=${highlighter.textAttributesKey?.externalName}," +
                    "foreground=${attributes?.foregroundColor?.rgb},background=${attributes?.backgroundColor?.rgb}," +
                    "effect=${attributes?.effectType},effectColor=${attributes?.effectColor?.rgb}," +
                    "renderer=${highlighter.customRenderer?.javaClass?.name}"
            }
    }

    /** Observes all daemon dirty scopes without starting, restarting, or disabling analysis. */
    @JvmStatic
    fun daemonDiagnostics(): String = readEdt {
        val editor = editor()
        val project = checkNotNull(editor.project)
        val document = editor.document
        val selected = FileEditorManager.getInstance(project).selectedEditor
        val selectedMatches = (selected as? TextEditor)?.editor === editor
        val indexing = DumbService.getInstance(project).isDumb
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val committed = PsiDocumentManager.getInstance(project).isCommitted(document)
        val completed = selected != null && selectedMatches &&
            DaemonCodeAnalyzerEx.isHighlightingCompleted(selected, project)
        val ready = !indexing && committed && completed
        val highlighters = DocumentMarkupModel.forDocument(document, project, false).allHighlighters
        "ready=$ready;stamp=${document.modificationStamp};selectedMatches=$selectedMatches;" +
            "indexing=$indexing;committed=$committed;highlightingCompleted=$completed;" +
            "projectSdk=${sdk?.name};projectSdkHome=${sdk?.homePath};" +
            "documentMarkupCount=${highlighters.size}\n" +
            highlighters.take(128).joinToString("\n") { mark ->
                val attributes = mark.getTextAttributes(editor.colorsScheme)
                "valid=${mark.isValid},range=${mark.startOffset}..${mark.endOffset}," +
                    "layer=${mark.layer},key=${mark.textAttributesKey?.externalName}," +
                    "foreground=${attributes?.foregroundColor?.rgb},effect=${attributes?.effectType}," +
                    "effectColor=${attributes?.effectColor?.rgb}"
            }
    }

    @JvmStatic
    fun state(): String = readEdt { stateOnEdt(editor()) }

    private fun stateOnEdt(editor: Editor): String {
        val rendered = editor.markupModel.allHighlighters.count {
            it.customRenderer?.javaClass?.name?.startsWith("com.sijunyang.bracketpairguides.") == true
        }
        val tokens = editor.markupModel.allHighlighters.count {
            it.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true
        }
        val pairs = editor.markupModel.allHighlighters.count {
            val attributes = it.getTextAttributes(editor.colorsScheme)
            it.layer == HighlighterLayer.ELEMENT_UNDER_CARET && it.endOffset - it.startOffset == 1 &&
                (attributes?.effectType == EffectType.BOXED || attributes?.backgroundColor != null)
        }
        return "${editor.document.modificationStamp}:$rendered:${GuideUiSettings.current().enabled}:${nativeState()}:$tokens:${editor.contentComponent.isFocusOwner}:${editor.contentComponent.isShowing}:$pairs"
    }

    private fun nativeState(): String {
        val insight = CodeInsightSettings.getInstance()
        return "${insight.HIGHLIGHT_BRACES}:${insight.HIGHLIGHT_SCOPE}:${EditorSettingsExternalizable.getInstance().isIndentGuidesShown}"
    }

    private fun editor(): Editor = checkNotNull(EditorFactory.getInstance().allEditors.firstOrNull {
        FileDocumentManager.getInstance().getFile(it.document)?.name == "Contract.java" && !it.isDisposed
    })

    private fun <T> edt(action: () -> T): T = onEdt(ModalityState.nonModal(), action)

    // Observations must remain available while Settings owns a modal event loop.
    private fun <T> readEdt(action: () -> T): T = onEdt(ModalityState.any(), action)

    private fun <T> onEdt(modality: ModalityState, action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        val answer = AtomicReference<Result<T>>()
        application.invokeAndWait({ answer.set(runCatching(action)) }, modality)
        return answer.get().getOrThrow()
    }
}
