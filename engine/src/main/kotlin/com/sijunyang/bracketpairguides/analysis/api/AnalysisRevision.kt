package com.sijunyang.bracketpairguides.analysis.api

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import org.jetbrains.annotations.ApiStatus

/** Capabilities independently requested by editor presentation. */
@ApiStatus.Internal
public data class AnalysisCapabilities(
    public val tokens: Boolean,
    public val activePair: Boolean,
    public val guidePosition: Boolean,
) {
    public val pairs: Boolean
        get() = tokens || activePair

    internal fun includes(required: AnalysisCapabilities): Boolean =
        (!required.tokens || tokens) &&
            (!required.activePair || activePair) &&
            (!required.guidePosition || guidePosition)
}

/** Small immutable identity used to reject stale or insufficient analysis. */
@ApiStatus.Internal
public class AnalysisRevision internal constructor(
    internal val documentStamp: Long,
    internal val tabSize: Int,
    internal val highlighterIdentity: Int,
    internal val fileType: FileType,
    public val capabilities: AnalysisCapabilities,
    internal val disabledLanguageIds: Set<String>,
) {
    public fun satisfies(required: AnalysisRevision): Boolean =
        documentStamp == required.documentStamp &&
            (!required.capabilities.guidePosition || tabSize == required.tabSize) &&
            highlighterIdentity == required.highlighterIdentity &&
            fileType === required.fileType &&
            disabledLanguageIds == required.disabledLanguageIds &&
            capabilities.includes(required.capabilities)

    /** Checks live editor state without allocating another revision object. */
    public fun satisfiesCurrent(
        editor: Editor,
        requiredFileType: FileType,
        requiredCapabilities: AnalysisCapabilities,
        requiredDisabledLanguageIds: Set<String>,
    ): Boolean =
        documentStamp == editor.document.modificationStamp &&
            (!requiredCapabilities.guidePosition ||
                tabSize == editor.settings.getTabSize(editor.project).coerceAtLeast(1)) &&
            highlighterIdentity == System.identityHashCode(editor.highlighter) &&
            fileType === requiredFileType &&
            disabledLanguageIds == requiredDisabledLanguageIds &&
            capabilities.includes(requiredCapabilities)

    internal companion object {
        fun current(
            editor: Editor,
            fileType: FileType,
            capabilities: AnalysisCapabilities,
            disabledLanguageIds: Set<String>,
        ): AnalysisRevision = AnalysisRevision(
            documentStamp = editor.document.modificationStamp,
            tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1),
            highlighterIdentity = System.identityHashCode(editor.highlighter),
            fileType = fileType,
            capabilities = capabilities,
            disabledLanguageIds = disabledLanguageIds,
        )
    }
}
