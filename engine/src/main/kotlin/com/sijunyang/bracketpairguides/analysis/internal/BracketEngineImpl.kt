package com.sijunyang.bracketpairguides.analysis.internal

import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.AnalysisSnapshotBuilder
import com.sijunyang.bracketpairguides.analysis.BracketLanguageSupport
import com.sijunyang.bracketpairguides.analysis.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analysis.EditorHighlighterActiveBracketPairResolver
import com.sijunyang.bracketpairguides.analysis.api.ActivePairRequest
import com.sijunyang.bracketpairguides.analysis.api.ActivePairResult
import com.sijunyang.bracketpairguides.analysis.api.AnalysisResult
import com.sijunyang.bracketpairguides.analysis.api.AnalyzeRequest
import com.sijunyang.bracketpairguides.analysis.api.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.api.BracketEngine

/** Stateless application-service implementation of the typed engine boundary. */
internal class BracketEngineImpl : BracketEngine {
    public override fun analyze(
        request: AnalyzeRequest,
        progress: ProgressIndicator,
    ): AnalysisResult {
        val disabledLanguageIds = request.disabledLanguageIds
        val pairProvider = BracketPairAnalyzer(
            editor = request.editor,
            fileType = request.fileType,
        ) { capabilityId ->
            capabilityId !in disabledLanguageIds
        }
        return AnalysisSnapshotBuilder.build(
            editor = request.editor,
            pairProvider = pairProvider,
            revision = request.revision,
            progress = progress,
        )
    }

    public override fun resolveActivePair(request: ActivePairRequest): ActivePairResult {
        val disabledLanguageIds = request.disabledLanguageIds
        return EditorHighlighterActiveBracketPairResolver(
            fileType = request.fileType,
            isLanguageEnabled = { capabilityId -> capabilityId !in disabledLanguageIds },
        ).findInnermost(request.editor, request.caretOffset)
    }

    public override fun installedLanguages(): List<BraceLanguageFamily> =
        BracketLanguageSupport.installedFamilies()
}
