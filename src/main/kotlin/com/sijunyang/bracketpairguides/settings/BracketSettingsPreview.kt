package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshot
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.OnePixelDivider
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Editable, lexer-backed preview for draft settings.
 *
 * Recognition is rerun only after a document, file-type, or language-selection
 * change. Caret and appearance changes reuse the latest immutable snapshot.
 */
internal class BracketSettingsPreview(
    pairProviderFactory: PreviewPairProviderFactory =
        PreviewPairProviderFactory { editor, fileType, disabledLanguageIds ->
            BracketPairAnalyzer(editor, fileType) { capabilityId ->
                capabilityId !in disabledLanguageIds
            }
        },
) : JPanel(BorderLayout()), Disposable {
    private val lifetime = Disposer.newDisposable("Bracket settings preview")
    private val recognitionAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, lifetime)
    private val recognizer = PreviewRecognizer(pairProviderFactory)
    private val examples = PreviewExample.available()
    private val buffers = examples.associate { example ->
        example.id to PreviewBuffer(example.source, example.initialCaretOffset)
    }.toMutableMap()

    internal val exampleSelector = JComboBox(examples.toTypedArray())
    internal val resetExampleButton = JButton("Reset")
    internal val previewEditor: EditorEx
    private var currentExample = examples.first()
    @Volatile
    private var currentFileType: FileType = currentExample.resolveFileType()
    @Volatile
    private var currentSettings = PluginOptions()
    @Volatile
    private var recognitionGeneration = 0L
    private var changingDocument = false
    @Volatile
    private var disposed = false
    private lateinit var decoration: PreviewDecorationController

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (changingDocument || disposed) return
            scheduleRecognition()
        }
    }
    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            if (changingDocument || disposed) return
            buffers.getValue(currentExample.id).caretOffset =
                event.editor.caretModel.primaryCaret.offset
            decoration.caretMoved()
        }
    }

    init {
        val factory = EditorFactory.getInstance()
        previewEditor = factory.createEditor(
            factory.createDocument(currentExample.source),
            null,
            EditorKind.PREVIEW,
        ) as EditorEx
        installFileTypeHighlighter(currentFileType)
        previewEditor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isIndentGuidesShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = true
            isUseSoftWraps = false
            isVirtualSpace = false
            isDndEnabled = false
            isBlinkCaret = true
        }
        previewEditor.setHorizontalScrollbarVisible(true)
        previewEditor.caretModel.moveToOffset(currentExample.initialCaretOffset)
        previewEditor.document.addDocumentListener(documentListener)
        previewEditor.caretModel.addCaretListener(caretListener)
        decoration = PreviewDecorationController(previewEditor)
        ApplicationManager.getApplication().messageBus.connect(lifetime).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !previewEditor.isDisposed) {
                        decoration.updateOptions(currentSettings)
                    }
                }
            },
        )

        configureLayout()
        wireControls()
        recognizeSynchronously()
    }

    fun update(options: PluginOptions) {
        if (disposed || previewEditor.isDisposed) return
        val languagesChanged =
            currentSettings.disabledLanguageIds != options.disabledLanguageIds
        currentSettings = options
        decoration.updateOptions(options)
        if (languagesChanged) recognizeAfterDocumentReplacement()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        recognitionGeneration++
        recognitionAlarm.cancelAllRequests()
        Disposer.dispose(lifetime)
        previewEditor.document.removeDocumentListener(documentListener)
        previewEditor.caretModel.removeCaretListener(caretListener)
        decoration.dispose()
        EditorFactory.getInstance().releaseEditor(previewEditor)
    }

    private fun configureLayout() {
        val toolbar = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(
                JBLabel("Example:"),
                GridBagConstraints().apply {
                    gridx = 0
                    weightx = 0.0
                    anchor = GridBagConstraints.WEST
                    insets = Insets(0, 0, 0, JBUI.scale(8))
                },
            )
            add(
                exampleSelector,
                GridBagConstraints().apply {
                    gridx = 1
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(0, 0, 0, JBUI.scale(8))
                },
            )
            add(
                resetExampleButton,
                GridBagConstraints().apply {
                    gridx = 2
                    weightx = 0.0
                    anchor = GridBagConstraints.EAST
                },
            )
        }

        previewEditor.scrollPane.border = JBUI.Borders.empty()
        previewEditor.component.apply {
            minimumSize = JBUI.size(0, MINIMUM_EDITOR_HEIGHT)
            preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_EDITOR_HEIGHT)
        }
        exampleSelector.toolTipText = "Examples available from installed language plugins"
        resetExampleButton.toolTipText = "Restore this format's boilerplate"
        previewEditor.component.accessibleContext.accessibleName =
            "Editable bracket pair preview"

        border = JBUI.Borders.customLine(
            OnePixelDivider.BACKGROUND,
            0,
            1,
            0,
            0,
        )
        minimumSize = JBUI.size(MINIMUM_WIDTH, MINIMUM_HEIGHT)
        preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(toolbar, BorderLayout.NORTH)
        add(previewEditor.component, BorderLayout.CENTER)
    }

    private fun wireControls() {
        exampleSelector.addActionListener {
            val selected = exampleSelector.selectedItem as? PreviewExample
                ?: return@addActionListener
            if (selected != currentExample) switchExample(selected)
        }
        resetExampleButton.addActionListener {
            val buffer = buffers.getValue(currentExample.id)
            buffer.text = currentExample.source
            buffer.caretOffset = currentExample.initialCaretOffset
            replaceDocument(buffer)
            recognizeAfterDocumentReplacement()
        }
    }

    private fun switchExample(nextExample: PreviewExample) {
        val currentBuffer = buffers.getValue(currentExample.id)
        currentBuffer.text = previewEditor.document.text
        currentBuffer.caretOffset = previewEditor.caretModel.primaryCaret.offset

        currentExample = nextExample
        currentFileType = nextExample.resolveFileType()
        recognitionGeneration++
        recognitionAlarm.cancelAllRequests()
        replaceDocument(
            buffer = buffers.getValue(nextExample.id),
            nextFileType = currentFileType,
        )
        recognizeAfterDocumentReplacement()
    }

    // Document has a Java setter without a Kotlin-writable property at the
    // Kotlin 1.9 language level required by the minimum supported IDE.
    @Suppress("UsePropertyAccessSyntax")
    private fun replaceDocument(
        buffer: PreviewBuffer,
        nextFileType: FileType? = null,
    ) {
        decoration.clearRecognition()
        changingDocument = true
        try {
            ApplicationManager.getApplication().runWriteAction {
                nextFileType?.let(::installFileTypeHighlighter)
                previewEditor.document.setText(buffer.text)
            }
            previewEditor.caretModel.moveToOffset(
                buffer.caretOffset.coerceIn(0, previewEditor.document.textLength),
            )
            previewEditor.scrollingModel.scrollToCaret(
                com.intellij.openapi.editor.ScrollType.CENTER,
            )
        } finally {
            changingDocument = false
        }
    }

    private fun installFileTypeHighlighter(fileType: FileType) {
        previewEditor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(null, fileType)
    }

    private fun recognizeAfterDocumentReplacement() {
        if (previewEditor.document.textLength <= IMMEDIATE_RECOGNITION_LENGTH) {
            recognizeSynchronously()
        } else {
            scheduleRecognition(delayMillis = 0)
        }
    }

    private fun scheduleRecognition(
        delayMillis: Int = RECOGNITION_DEBOUNCE_MILLIS,
    ) {
        recognitionGeneration++
        decoration.clearRecognition()
        recognitionAlarm.cancelAllRequests()
        if (previewEditor.document.textLength > MAX_PREVIEW_LENGTH) {
            return
        }
        recognitionAlarm.addRequest(
            { submitRecognition() },
            delayMillis,
        )
    }

    private fun submitRecognition() {
        if (disposed || previewEditor.isDisposed) return
        val generation = recognitionGeneration
        val modificationStamp = previewEditor.document.modificationStamp
        val fileType = currentFileType
        val disabledLanguageIds = currentSettings.disabledLanguageIds
        ReadAction.nonBlocking<AnalysisSnapshot> {
            val indicator = ProgressManager.getInstance().progressIndicator
                ?: EmptyProgressIndicator()
            recognizer.recognize(
                previewEditor,
                fileType,
                disabledLanguageIds,
                indicator,
            )
        }.expireWith(lifetime)
            .expireWhen {
                disposed ||
                    generation != recognitionGeneration ||
                    modificationStamp != previewEditor.document.modificationStamp ||
                    fileType !== currentFileType ||
                    disabledLanguageIds != currentSettings.disabledLanguageIds
            }
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { result ->
                if (disposed || previewEditor.isDisposed) return@finishOnUiThread
                if (generation != recognitionGeneration ||
                    modificationStamp != previewEditor.document.modificationStamp ||
                    fileType !== currentFileType ||
                    disabledLanguageIds != currentSettings.disabledLanguageIds
                ) {
                    return@finishOnUiThread
                }
                applyRecognition(result)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun recognizeSynchronously() {
        if (disposed || previewEditor.isDisposed) return
        recognitionGeneration++
        recognitionAlarm.cancelAllRequests()
        if (previewEditor.document.textLength > MAX_PREVIEW_LENGTH) {
            decoration.clearRecognition()
            return
        }
        val result = ReadAction.compute<AnalysisSnapshot, RuntimeException> {
            recognizer.recognize(
                previewEditor,
                currentFileType,
                currentSettings.disabledLanguageIds,
                EmptyProgressIndicator(),
            )
        }
        applyRecognition(result)
    }

    private fun applyRecognition(result: AnalysisSnapshot) {
        decoration.updateRecognition(result)
    }

    private data class PreviewBuffer(
        var text: String,
        var caretOffset: Int,
    )

    companion object {
        private const val RECOGNITION_DEBOUNCE_MILLIS = 150
        private const val IMMEDIATE_RECOGNITION_LENGTH = 10_000
        private const val MAX_PREVIEW_LENGTH = 100_000
        private const val MINIMUM_WIDTH = 240
        private const val PREFERRED_WIDTH = 420
        private const val MINIMUM_HEIGHT = 320
        private const val PREFERRED_HEIGHT = 560
        private const val MINIMUM_EDITOR_HEIGHT = 240
        private const val PREFERRED_EDITOR_HEIGHT = 470
    }
}
