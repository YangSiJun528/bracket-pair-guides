package com.sijunyang.bracketpairguides.analysis

import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.sijunyang.bracketpairguides.analysis.api.BraceLanguageFamily

/** Discovers installed language families backed by the official matcher API. */
internal object BracketLanguageSupport {
    public fun installedFamilies(): List<BraceLanguageFamily> {
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

    internal fun capabilityId(language: Language, matcher: PairedBraceMatcher): String =
        capabilityOwner(language, matcher).id

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
