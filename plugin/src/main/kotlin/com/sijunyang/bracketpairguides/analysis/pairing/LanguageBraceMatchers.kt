package com.sijunyang.bracketpairguides.analysis.pairing

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
    val constraintDescription: String? = null,
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
     * Installed language families backed by the official matcher extension.
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
            val familyMembers = members
                .distinctBy(Language::getID)

            val owner = capabilityOwner(familyMembers.first()) ?: return@mapNotNull null
            SupportedBraceLanguage(
                id = ownerId,
                displayName = if (ownerId == CUSTOM_FILE_TYPE_LANGUAGE_ID) {
                    "Custom file types"
                } else {
                    owner.displayName.ifBlank { ownerId }
                },
                familyDisplayNames = familyMembers
                    .map { language -> language.displayName.ifBlank { language.id } }
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
                constraintDescription = if (ownerId == CUSTOM_FILE_TYPE_LANGUAGE_ID) {
                    "Custom syntax-table bracket tokens only; raw plain text is not scanned"
                } else {
                    null
                },
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

    private const val CUSTOM_FILE_TYPE_LANGUAGE_ID = "TEXT"
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

    fun isStructuralClose(tokenType: IElementType): Boolean =
        topology.isStructuralClose(tokenType)
}
