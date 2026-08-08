package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.annotations.ApiStatus

/**
 * Boundary between bracket recognition and editor decoration.
 *
 * The production implementation reads an editor token stream. Highlighting
 * tests can inject deterministic pairs without a lexer or language plugin.
 */
@ApiStatus.Internal
public fun interface BracketPairProvider {
    public fun collect(progress: ProgressIndicator): List<BracketPair>
}
