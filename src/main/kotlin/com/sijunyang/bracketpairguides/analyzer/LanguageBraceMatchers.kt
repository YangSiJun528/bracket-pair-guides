package com.sijunyang.bracketpairguides.analyzer

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.psi.tree.IElementType
import java.util.Locale

internal data class SupportedBraceLanguage(
    val id: String,
    val displayName: String,
    val familyDisplayNames: List<String>,
)

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
            capabilityId = capabilityOwner(language, pairedMatcher).id,
        )
    }

    /**
     * User-facing installed language families backed by the official matcher extension.
     * Dialects inheriting the same matcher are grouped under the highest matching base
     * language so the persisted ID is also the ID checked during token analysis.
     */
    fun supportedLanguages(): List<SupportedBraceLanguage> {
        val families = Language.getRegisteredLanguages()
            .asSequence()
            .filter { language -> language !== Language.ANY }
            .mapNotNull { language ->
                val matcher = LanguageBraceMatching.INSTANCE.forLanguage(language)
                    ?: return@mapNotNull null
                capabilityOwner(language, matcher) to language
            }
            .groupBy(
                keySelector = { (owner, _) -> owner.id },
                valueTransform = { (_, member) -> member },
            )

        return families.mapNotNull { (ownerId, members) ->
            val visibleMembers = members
                .filter { language -> language.associatedFileType != null }
                .distinctBy(Language::getID)
            if (visibleMembers.isEmpty()) return@mapNotNull null

            val owner = capabilityOwner(visibleMembers.first()) ?: return@mapNotNull null
            SupportedBraceLanguage(
                id = ownerId,
                displayName = owner.displayName.ifBlank { ownerId },
                familyDisplayNames = visibleMembers
                    .map { language -> language.displayName.ifBlank { language.id } }
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
            )
        }
            .sortedWith(
                compareBy<SupportedBraceLanguage>(
                    { language -> language.displayName.lowercase(Locale.ROOT) },
                    SupportedBraceLanguage::id,
                ),
            )
    }

    fun capabilityOwner(language: Language): Language? {
        val matcher = LanguageBraceMatching.INSTANCE.forLanguage(language) ?: return null
        return capabilityOwner(language, matcher)
    }

    private fun capabilityOwner(
        language: Language,
        matcher: Any,
    ): Language {
        var owner = language
        var base = owner.baseLanguage
        while (
            base != null &&
            LanguageBraceMatching.INSTANCE.forLanguage(base) === matcher
        ) {
            owner = base
            base = owner.baseLanguage
        }
        return owner
    }
}

internal class ResolvedLanguageBraceMatcher(
    val matcher: BraceMatcher,
    private val topology: BracePairTopology,
    val capabilityId: String,
) {
    val isPair: (IElementType, IElementType) -> Boolean = matcher::isPairBraces
    val isStructuralPair: (IElementType, IElementType) -> Boolean =
        topology::isStructuralPair

    fun isPureSymmetric(tokenType: IElementType): Boolean =
        topology.isPureSymmetric(tokenType)

    fun isStructuralOpen(tokenType: IElementType): Boolean =
        topology.isStructuralOpen(tokenType)
}
