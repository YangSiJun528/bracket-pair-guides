package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.api.BracketPair

/**
 * Internal seam between token recognition and immutable result construction.
 * Engine tests can inject deterministic pairs without a lexer or language plugin.
 */
internal fun interface BracketPairProvider {
    public fun collect(progress: ProgressIndicator): List<BracketPair>
}
