package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BracketLanguageSupport
import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.psi.tree.IElementType
import org.jetbrains.annotations.ApiStatus

/** The sole capability gate for bracket recognition. */
@ApiStatus.Internal
public object LanguageBraceMatchers {
    public fun resolve(language: Language): ResolvedLanguageBraceMatcher? {
        val pairedMatcher =
            LanguageBraceMatching.INSTANCE.forLanguage(language) ?: return null
        val matcher = (pairedMatcher as? BraceMatcher)
            ?: PairedBraceMatcherAdapter(pairedMatcher, language)
        return ResolvedLanguageBraceMatcher(
            matcher = matcher,
            topology = BracePairTopology(pairedMatcher.pairs),
            capabilityId = BracketLanguageSupport.capabilityId(language, pairedMatcher),
        )
    }
}

@ApiStatus.Internal
public class ResolvedLanguageBraceMatcher(
    public val matcher: BraceMatcher,
    private val topology: BracePairTopology,
    public val capabilityId: String,
) {
    public val isPair: (IElementType, IElementType) -> Boolean = matcher::isPairBraces
    public val isStructuralPair: (IElementType, IElementType) -> Boolean =
        topology::isStructuralPair

    public fun isPureSymmetric(tokenType: IElementType): Boolean =
        topology.isPureSymmetric(tokenType)

    public fun isStructuralOpen(tokenType: IElementType): Boolean =
        topology.isStructuralOpen(tokenType)

    public fun isStructuralClose(tokenType: IElementType): Boolean =
        topology.isStructuralClose(tokenType)
}
