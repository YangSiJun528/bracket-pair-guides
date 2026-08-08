package com.sijunyang.bracketpairguides.analyzer

import com.intellij.openapi.progress.ProgressIndicator

/**
 * Boundary between bracket recognition and editor decoration.
 *
 * The production implementation reads an editor token stream. Highlighting
 * tests can inject deterministic pairs without a lexer or language plugin.
 */
internal fun interface BracketPairProvider {
    fun collect(progress: ProgressIndicator): List<BracketPair>
}
