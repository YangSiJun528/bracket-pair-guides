package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingRules

/** Installed brace-language definitions and their shared capability families. */
internal class BraceLanguageCatalog {
    fun definitionFor(language: Language): BraceLanguageDefinition? {
        val pairedMatcher =
            LanguageBraceMatching.INSTANCE.forLanguage(language) ?: return null
        val matcher = (pairedMatcher as? BraceMatcher)
            ?: PairedBraceMatcherAdapter(pairedMatcher, language)
        return BraceLanguageDefinition(
            matcher = matcher,
            topology = BracePairTopology(pairedMatcher.pairs),
            capabilityId = capabilityOwner(language, pairedMatcher).id,
        )
    }

    fun installedFamilies(): List<BraceLanguageFamily> {
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
            val familyMembers = members.distinctBy(Language::getID)
            val owner = capabilityOwner(familyMembers.first()) ?: return@mapNotNull null
            BraceLanguageFamily(
                id = ownerId,
                displayName = owner.displayName.ifBlank { ownerId },
                memberDisplayNames = familyMembers
                    .map { language -> language.displayName.ifBlank { language.id } }
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
            )
        }
            .sortedBy(BraceLanguageFamily::id)
    }

    private fun capabilityOwner(language: Language): Language? {
        val matcher = LanguageBraceMatching.INSTANCE.forLanguage(language) ?: return null
        return capabilityOwner(language, matcher)
    }

    private fun capabilityOwner(
        language: Language,
        matcher: PairedBraceMatcher,
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

/** One language family's matcher, topology, capability identity, and pair rules. */
internal class BraceLanguageDefinition(
    val matcher: BraceMatcher,
    private val topology: BracePairTopology,
    val capabilityId: String,
) : PairingRules<IElementType> {
    override fun isPair(openToken: IElementType, closeToken: IElementType): Boolean =
        matcher.isPairBraces(openToken, closeToken)

    override fun isStructuralPair(
        openToken: IElementType,
        closeToken: IElementType,
    ): Boolean = topology.isStructuralPair(openToken, closeToken)

    fun isPureSymmetric(tokenType: IElementType): Boolean =
        topology.isPureSymmetric(tokenType)

    fun isStructuralOpen(tokenType: IElementType): Boolean =
        topology.isStructuralOpen(tokenType)

    fun isStructuralClose(tokenType: IElementType): Boolean =
        topology.isStructuralClose(tokenType)
}
