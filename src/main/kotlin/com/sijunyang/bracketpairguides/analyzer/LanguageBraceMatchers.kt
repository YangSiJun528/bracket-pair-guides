package com.sijunyang.bracketpairguides.analyzer

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.psi.tree.IElementType

/** The sole capability gate for bracket recognition. */
internal object LanguageBraceMatchers {
    fun isRegistered(language: Language): Boolean =
        LanguageBraceMatching.INSTANCE.forLanguage(language) != null

    fun resolve(language: Language): ResolvedLanguageBraceMatcher? {
        val pairedMatcher =
            LanguageBraceMatching.INSTANCE.forLanguage(language) ?: return null
        val matcher = (pairedMatcher as? BraceMatcher)
            ?: PairedBraceMatcherAdapter(pairedMatcher, language)
        return ResolvedLanguageBraceMatcher(
            matcher = matcher,
            topology = BracePairTopology(pairedMatcher.pairs),
        )
    }
}

internal class ResolvedLanguageBraceMatcher(
    val matcher: BraceMatcher,
    private val topology: BracePairTopology,
) {
    val isPair: (IElementType, IElementType) -> Boolean = matcher::isPairBraces

    fun isPureSymmetric(tokenType: IElementType): Boolean =
        topology.isPureSymmetric(tokenType)
}
