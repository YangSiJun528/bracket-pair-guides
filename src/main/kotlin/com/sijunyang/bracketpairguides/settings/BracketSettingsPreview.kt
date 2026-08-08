package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.LanguageBraceMatchers
import com.sijunyang.bracketpairguides.renderer.AnalysisCapabilities
import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshot
import com.sijunyang.bracketpairguides.renderer.DocumentChange
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
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
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.OnePixelDivider
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.concurrent.CancellationException
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
    private val recognizer = PreviewRecognizer(pairProviderFactory)
    private val examples = PreviewExample.available()
    private val buffers = examples.associate { example ->
        example.id to PreviewBuffer(
            text = example.source,
            caretOffset = example.initialCaretOffset,
        )
    }.toMutableMap()

    internal val exampleSelector = JComboBox(examples.toTypedArray())
    internal val resetExampleButton = JButton("Reset")
    internal val analysisStatusLabel = JBLabel()
    internal val previewEditor: EditorEx
    private var currentExample = examples.first()
    private var currentFileType: FileType = currentExample.resolveFileType()
    private var currentSettings = PluginOptions()
    private var recognitionGeneration = 0L
    private var analyzingGeneration: Long? = null
    private var failedGeneration: Long? = null
    private var recognitionJob: Job? = null
    private var changingDocument = false
    private var disposed = false
    private lateinit var decoration: PreviewDecorationController

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (changingDocument || disposed) return
            updateLengthState()
            if (event.document.textLength <= MAX_PREVIEW_LENGTH) {
                decoration.documentChanged(DocumentChange.from(event))
            }
            scheduleRecognition()
        }
    }
    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            if (changingDocument || disposed) return
            if (event.caret?.let { it !== event.editor.caretModel.primaryCaret } == true) return
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
        previewEditor.document.addDocumentListener(documentListener, lifetime)
        previewEditor.caretModel.addCaretListener(caretListener)
        decoration = PreviewDecorationController(previewEditor)
        ApplicationManager.getApplication().messageBus.connect(lifetime).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !previewEditor.isDisposed) {
                        decoration.updateOptions(currentSettings, refreshColors = true)
                    }
                }
            },
        )

        configureLayout()
        wireControls()
        updateLengthState()
        recognizeSynchronously()
    }

    fun update(options: PluginOptions) {
        if (disposed || previewEditor.isDisposed) return
        val previousCapabilities = AnalysisCapabilities.from(currentSettings)
        val nextCapabilities = AnalysisCapabilities.from(options)
        val languagesChanged =
            currentSettings.disabledLanguageIds != options.disabledLanguageIds
        currentSettings = options
        decoration.updateOptions(options)
        val capabilitiesExpanded = !previousCapabilities.includes(nextCapabilities)
        if (languagesChanged ||
            (capabilitiesExpanded && decoration.requiresRecognitionRefresh())
        ) {
            recognizeAfterDocumentReplacement()
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        recognitionGeneration++
        analyzingGeneration = null
        failedGeneration = null
        cancelPendingRecognition()
        Disposer.dispose(lifetime)
        previewEditor.caretModel.removeCaretListener(caretListener)
        decoration.dispose()
        EditorFactory.getInstance().releaseEditor(previewEditor)
    }

    private fun configureLayout() {
        val exampleLabel = JBLabel("Example:").apply {
            displayedMnemonic = 'E'.code
            labelFor = exampleSelector
        }
        val toolbar = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(
                exampleLabel,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weightx = 0.0
                    anchor = GridBagConstraints.WEST
                    insets = Insets(0, 0, 0, JBUI.scale(8))
                },
            )
            add(
                exampleSelector,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = 0
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
                    gridy = 0
                    weightx = 0.0
                    anchor = GridBagConstraints.EAST
                },
            )
            add(
                analysisStatusLabel,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 1
                    gridwidth = 3
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(JBUI.scale(6), 0, 0, 0)
                },
            )
        }

        previewEditor.scrollPane.border = JBUI.Borders.empty()
        previewEditor.component.apply {
            minimumSize = JBUI.size(0, MINIMUM_EDITOR_HEIGHT)
            preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_EDITOR_HEIGHT)
        }
        exampleSelector.toolTipText =
            "Bundled examples available for installed brace matchers"
        exampleSelector.accessibleContext.apply {
            accessibleName = "Preview example"
            accessibleDescription = exampleSelector.toolTipText
        }
        resetExampleButton.toolTipText = "Restore the current bundled example"
        resetExampleButton.accessibleContext.apply {
            accessibleName = "Reset preview example"
            accessibleDescription = resetExampleButton.toolTipText
        }
        analysisStatusLabel.accessibleContext.accessibleName = "Preview analysis status"
        val previewAccessibleName = "Editable bracket pair preview"
        previewEditor.component.accessibleContext.accessibleName = previewAccessibleName
        previewEditor.contentComponent.accessibleContext.accessibleName =
            previewAccessibleName

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
            if (disposed || previewEditor.isDisposed) return@addActionListener
            val selected = exampleSelector.selectedItem as? PreviewExample
                ?: return@addActionListener
            if (previewEditor.document.textLength > MAX_PREVIEW_LENGTH) {
                if (selected != currentExample) exampleSelector.selectedItem = currentExample
                return@addActionListener
            }
            if (selected != currentExample) switchExample(selected)
        }
        resetExampleButton.addActionListener {
            if (disposed || previewEditor.isDisposed) return@addActionListener
            val buffer = buffers.getValue(currentExample.id)
            buffer.text = currentExample.source
            buffer.caretOffset = currentExample.initialCaretOffset
            buffer.scrollPosition = null
            replaceDocument(buffer)
            recognizeAfterDocumentReplacement()
        }
    }

    private fun switchExample(nextExample: PreviewExample) {
        val currentBuffer = buffers.getValue(currentExample.id)
        currentBuffer.text = previewEditor.document.text
        currentBuffer.caretOffset = previewEditor.caretModel.primaryCaret.offset
        currentBuffer.scrollPosition = PreviewScrollPosition(
            horizontalOffset = previewEditor.scrollingModel.horizontalScrollOffset,
            verticalOffset = previewEditor.scrollingModel.verticalScrollOffset,
        )

        currentExample = nextExample
        currentFileType = nextExample.resolveFileType()
        recognitionGeneration++
        cancelPendingRecognition()
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
            val scrollPosition = buffer.scrollPosition
            if (scrollPosition == null) {
                previewEditor.scrollingModel.scrollToCaret(
                    com.intellij.openapi.editor.ScrollType.CENTER,
                )
            } else {
                val scrollingModel = previewEditor.scrollingModel
                scrollingModel.disableAnimation()
                try {
                    scrollingModel.scrollHorizontally(
                        scrollPosition.horizontalOffset,
                    )
                    scrollingModel.scrollVertically(
                        scrollPosition.verticalOffset,
                    )
                } finally {
                    scrollingModel.enableAnimation()
                }
            }
        } finally {
            changingDocument = false
            updateLengthState()
        }
    }

    private fun updateLengthState() {
        val analysisPaused = previewEditor.document.textLength > MAX_PREVIEW_LENGTH
        val disabledExampleLanguage = currentExampleDisabledLanguageName()
        exampleSelector.isEnabled = !analysisPaused
        val (status, statusDescription) = when {
            analysisPaused -> Pair(
                "Analysis paused (>100,000 chars).",
                "Analysis and example switching are paused above 100,000 characters. " +
                    "Shorten the text or Reset to resume.",
            )
            analyzingGeneration == recognitionGeneration -> Pair(
                "Analyzing preview...",
                "Analyzing preview in the background...",
            )
            failedGeneration == recognitionGeneration -> Pair(
                "Preview analysis failed.",
                "Preview analysis failed. Edit the text or Reset to retry.",
            )
            disabledExampleLanguage != null -> {
                Pair(
                    "$disabledExampleLanguage is disabled.",
                    "Enable $disabledExampleLanguage in Languages to show bracket highlighting " +
                        "for this example.",
                )
            }
            else -> "" to null
        }
        analysisStatusLabel.text = status
        analysisStatusLabel.toolTipText = statusDescription
        analysisStatusLabel.accessibleContext.accessibleDescription = statusDescription
        analysisStatusLabel.isVisible = status.isNotEmpty()
    }

    private fun currentExampleDisabledLanguageName(): String? {
        val language = (currentFileType as? LanguageFileType)?.language ?: return null
        val owner = LanguageBraceMatchers.capabilityOwner(language) ?: return null
        if (currentSettings.isLanguageEnabled(owner.id)) return null
        return owner.displayName.ifBlank { owner.id }
    }

    private fun installFileTypeHighlighter(fileType: FileType) {
        previewEditor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(null, fileType)
    }

    private fun recognizeAfterDocumentReplacement() {
        scheduleRecognition(
            delayMillis = 0,
            showProgress = currentExampleDisabledLanguageName() == null,
        )
    }

    private fun scheduleRecognition(
        delayMillis: Int = RECOGNITION_DEBOUNCE_MILLIS,
        showProgress: Boolean = false,
    ) {
        recognitionGeneration++
        cancelPendingRecognition()
        failedGeneration = null
        if (previewEditor.document.textLength > MAX_PREVIEW_LENGTH) {
            analyzingGeneration = null
            decoration.clearRecognition()
            updateLengthState()
            return
        }
        analyzingGeneration = recognitionGeneration.takeIf {
            showProgress ||
                previewEditor.document.textLength > IMMEDIATE_RECOGNITION_LENGTH
        }
        updateLengthState()
        val generation = recognitionGeneration
        val modificationStamp = previewEditor.document.modificationStamp
        val fileType = currentFileType
        val disabledLanguageIds = currentSettings.disabledLanguageIds
        recognitionJob = PreviewRecognitionService.getInstance().launch {
            delay(delayMillis.toLong())
            val outcome = readAction {
                recognizeSafely(
                    fileType,
                    disabledLanguageIds,
                    coroutineProgressIndicator(),
                )
            }
            withContext(Dispatchers.EDT) {
                if (disposed || previewEditor.isDisposed) return@withContext
                if (generation != recognitionGeneration ||
                    modificationStamp != previewEditor.document.modificationStamp ||
                    fileType !== currentFileType ||
                    disabledLanguageIds != currentSettings.disabledLanguageIds
                ) {
                    return@withContext
                }
                recognitionJob = null
                applyRecognition(outcome, generation)
            }
        }
    }

    private fun recognizeSynchronously() {
        if (disposed || previewEditor.isDisposed) return
        recognitionGeneration++
        analyzingGeneration = null
        failedGeneration = null
        cancelPendingRecognition()
        if (previewEditor.document.textLength > MAX_PREVIEW_LENGTH) {
            decoration.clearRecognition()
            updateLengthState()
            return
        }
        updateLengthState()
        val generation = recognitionGeneration
        val outcome = ReadAction.compute<RecognitionOutcome, RuntimeException> {
            recognizeSafely(
                currentFileType,
                currentSettings.disabledLanguageIds,
                EmptyProgressIndicator(),
            )
        }
        applyRecognition(outcome, generation)
    }

    private fun cancelPendingRecognition() {
        recognitionJob?.cancel()
        recognitionJob = null
    }

    private fun coroutineProgressIndicator(): ProgressIndicator =
        object : ProgressIndicator by EmptyProgressIndicator() {
            override fun checkCanceled() {
                ProgressManager.checkCanceled()
            }
        }

    private fun recognizeSafely(
        fileType: FileType,
        disabledLanguageIds: Set<String>,
        indicator: ProgressIndicator,
    ): RecognitionOutcome = try {
        RecognitionOutcome.Success(
            recognizer.recognize(
                previewEditor,
                fileType,
                disabledLanguageIds,
                indicator,
            ),
        )
    } catch (exception: ProcessCanceledException) {
        throw exception
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        RecognitionOutcome.Failure(exception)
    }

    private fun applyRecognition(outcome: RecognitionOutcome, generation: Long) {
        if (generation != recognitionGeneration) return
        when (outcome) {
            is RecognitionOutcome.Success -> {
                if (!decoration.tryUpdateRecognition(outcome.snapshot)) {
                    scheduleRecognition(delayMillis = 0, showProgress = true)
                    return
                }
                analyzingGeneration = null
                failedGeneration = null
            }
            is RecognitionOutcome.Failure -> {
                analyzingGeneration = null
                failedGeneration = generation
                decoration.clearRecognition()
                LOG.warn("Preview recognition failed", outcome.exception)
            }
        }
        updateLengthState()
    }

    private sealed interface RecognitionOutcome {
        data class Success(val snapshot: AnalysisSnapshot) : RecognitionOutcome
        data class Failure(val exception: RuntimeException) : RecognitionOutcome
    }

    private data class PreviewBuffer(
        var text: String,
        var caretOffset: Int,
        var scrollPosition: PreviewScrollPosition? = null,
    )

    private data class PreviewScrollPosition(
        val horizontalOffset: Int,
        val verticalOffset: Int,
    )

    companion object {
        private val LOG = Logger.getInstance(BracketSettingsPreview::class.java)
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
