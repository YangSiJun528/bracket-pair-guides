package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorActivitySource
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.editor.events.StickyLineSourceRanges
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.editor.policy.EditorPresentationPolicy
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

/**
 * Collects an immutable result in the platform highlighting lifecycle.
 * Platform-managed passes may be constructed off EDT and are collected off EDT,
 * then applied on EDT. The analysis remains synchronous and observes the pass's
 * [ProgressIndicator].
 */
internal class BracketGuideHighlightingPass(
    project: Project,
    private val editor: Editor,
    private val fileType: FileType,
    private val sourceFile: VirtualFile?,
    private val analyze: (AnalysisInput, ProgressIndicator) -> AnalysisOutcome,
    private val capabilities: (Editor) -> EditorCapabilities = EditorSurfaceClassifier::capabilities,
    private val activity: (Editor) -> EditorActivity = EditorActivitySource::capture,
    private val visibleRange: (Editor) -> TextRange = Editor::calculateVisibleRange,
    private val stickySourceRanges: (Editor) -> List<TextRange> =
        StickyLineSourceRanges::calculate,
) : TextEditorHighlightingPass(project, editor.document, false) {
    private var collected: AnalysisOutcome? = null
    private var collectedStamp: AnalysisStamp? = null

    init {
        if (EditorEffectGuard.allowsEffects() && ApplicationManager.getApplication().isDispatchThread &&
            !editor.isDisposed
        ) {
            installSession()
        }
    }

    override fun doCollectInformation(progress: ProgressIndicator) {
        collected = null
        collectedStamp = null
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed ||
            capabilities(editor) == EditorCapabilities.NONE
        ) {
            return
        }
        val input = currentInput()
        collectedStamp = input.stamp
        if (sourceIsTooLarge()) {
            collected =
                AnalysisOutcome.Unavailable(
                    input.stamp,
                    AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE,
                )
            return
        }
        if (EditorGuideSessions.canSkipAnalysis(editor, input.stamp)) {
            return
        }
        collected = analyze(input, progress)
    }

    override fun doApplyInformationToEditor() {
        val result = collected
        val passStamp = collectedStamp
        collected = null
        collectedStamp = null
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed) return
        val ideCodeInsightLimitApplies = sourceIsTooLarge()
        if (ideCodeInsightLimitApplies) {
            if (editor.isDisposed) return
            val currentStamp = currentInput(EditorSurfaceClassifier.fileType(editor)).stamp
            val session = installSession() ?: return
            session.updateDependenciesIfCurrent(
                visibleRange = visibleRange,
                stickySourceRanges = supportedStickySourceRanges(),
                passStamp = currentStamp,
            )
            session.accept(
                AnalysisOutcome.Unavailable(
                    currentStamp,
                    AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE,
                ),
            )
            return
        }
        val collectedIdeSizeRefusal =
            result is AnalysisOutcome.Unavailable &&
                result.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE
        val effectiveResult = result.takeUnless { collectedIdeSizeRefusal }
        if (editor.isDisposed || passStamp?.let { stamp ->
                when {
                    collectedIdeSizeRefusal -> isExactCurrent(stamp)

                    effectiveResult is AnalysisOutcome.Limited ||
                        effectiveResult is AnalysisOutcome.Unavailable -> isExactCurrent(stamp)

                    else -> isCurrent(stamp)
                }
            } == false
        ) {
            return
        }
        val session = installSession() ?: return
        if (passStamp != null) {
            session.updateDependenciesIfCurrent(
                visibleRange = visibleRange,
                stickySourceRanges = supportedStickySourceRanges(),
                passStamp = passStamp,
            )
        }
        effectiveResult?.let(session::accept)
    }

    private fun currentInput(currentFileType: FileType = fileType): AnalysisInput {
        val options = BracketGuideSettings.getInstance().options
        return AnalysisInput(
            editor = editor,
            fileType = currentFileType,
            coverage = coverage(options),
            disabledLanguageIds = options.disabledLanguageIds,
        )
    }

    private fun isCurrent(passStamp: AnalysisStamp): Boolean {
        val options = BracketGuideSettings.getInstance().options
        return passStamp.matchesCurrent(
            editor,
            EditorSurfaceClassifier.fileType(editor),
            coverage(options),
            options.disabledLanguageIds,
        )
    }

    private fun isExactCurrent(passStamp: AnalysisStamp): Boolean {
        val options = BracketGuideSettings.getInstance().options
        val requiredCoverage = coverage(options)
        return passStamp.coverage == requiredCoverage &&
            passStamp.matchesCurrent(
                editor,
                EditorSurfaceClassifier.fileType(editor),
                requiredCoverage,
                options.disabledLanguageIds,
            )
    }

    private fun sourceIsTooLarge(): Boolean = sourceFile?.let { file ->
        if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)) {
            // IntelliJ's document-commit path uses textLength for current
            // in-memory content; saved content keeps VirtualFile byte size.
            SingleRootFileViewProvider.isTooLargeForIntelligence(
                file,
                editor.document.textLength.toLong(),
            )
        } else {
            SingleRootFileViewProvider.isTooLargeForIntelligence(file)
        }
    } == true

    private fun coverage(options: BracketGuidePreferences) = EditorPresentationPolicy.resolve(
        capabilities(editor),
        options,
        EditorActivity.INACTIVE,
    ).analysis

    private fun supportedStickySourceRanges(): (Editor) -> List<TextRange> =
        if (capabilities(editor).activePair) stickySourceRanges else { _ -> emptyList() }

    private fun installSession(): EditorGuideSession? {
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed ||
            capabilities(editor) == EditorCapabilities.NONE
        ) {
            return null
        }
        EditorGuideEvents.ensureInitialized(editor, observeStickyLines = capabilities(editor).activePair)
        return EditorGuideSessions.install(
            editor = editor,
            visibleRange = visibleRange,
            stickySourceRanges = supportedStickySourceRanges(),
            preferences = BracketGuideSettings.getInstance().options,
            activity = activity(editor),
            capabilities = capabilities(editor),
            matcherAvailabilityChanged = UnsupportedBackendNotificationProvider::update,
            nativeGuideConflictCandidate = { candidateEditor, guide ->
                NativeGuideConflictNotification.getInstance().consider(candidateEditor, guide)
            },
        )
    }
}
