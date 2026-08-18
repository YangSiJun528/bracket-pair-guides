package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingRules

/** Effective brace-matcher definitions and their shared capability families. */
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

    /** Resolves the same effective matcher used by the platform for this token. */
    fun definitionFor(
        fileType: FileType,
        iterator: HighlighterIterator,
        language: Language,
    ): BraceLanguageDefinition? {
        val matcher = BraceMatchingUtil.getBraceMatcher(fileType, iterator)
        if (matcher === DEFAULT_MATCHER) return null
        val topology = (matcher as? PairedBraceMatcher)?.let { pairedMatcher ->
            BracePairTopology(pairedMatcher.pairs)
        }
        return BraceLanguageDefinition(
            matcher = matcher,
            topology = topology,
            capabilityId = capabilityOwner(fileType, language).id,
        )
    }

    fun installedFamilies(): List<BraceLanguageFamily> {
        val languageMatchers = Language.getRegisteredLanguages()
            .asSequence()
            .filter { language -> language !== Language.ANY }
            .mapNotNull { language ->
                val matcher = LanguageBraceMatching.INSTANCE.forLanguage(language)
                    ?: return@mapNotNull null
                capabilityOwner(language, matcher) to language
            }

        val legacyMatchers = BraceMatcher.EP_NAME.extensionList
            .asSequence()
            .mapNotNull { extension ->
                FileTypeRegistry.getInstance().findFileTypeByName(extension.filetype)
            }
            .filterIsInstance<LanguageFileType>()
            .map(LanguageFileType::getLanguage)
            .filter { language ->
                LanguageBraceMatching.INSTANCE.forLanguage(language) == null
            }
            .map { language -> language to language }

        val families = (languageMatchers + legacyMatchers)
            .groupBy(
                keySelector = { (owner, _) -> owner.id },
            )

        return families.map { (ownerId, entries) ->
            val owner = entries.first().first
            val familyMembers = entries.map { (_, member) -> member }
                .distinctBy(Language::getID)
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

    private fun capabilityOwner(fileType: FileType, language: Language): Language {
        val languageMatcher = LanguageBraceMatching.INSTANCE.forLanguage(language)
        if (languageMatcher != null) {
            return capabilityOwner(language, languageMatcher)
        }

        if (hasLegacyMatcher(fileType)) {
            return (fileType as? LanguageFileType)?.language ?: language
        }

        if (fileType is LanguageFileType && language !== fileType.language) {
            val associatedFileType = language.associatedFileType
            if (associatedFileType != null && hasLegacyMatcher(associatedFileType)) {
                return language
            }

            val hostMatcher = LanguageBraceMatching.INSTANCE.forLanguage(fileType.language)
            if (hostMatcher != null) {
                return capabilityOwner(fileType.language, hostMatcher)
            }
        }

        return (fileType as? LanguageFileType)?.language ?: language
    }

    private fun hasLegacyMatcher(fileType: FileType): Boolean =
        BraceMatcher.EP_NAME.extensionList.any { extension ->
            extension.filetype == fileType.name
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

    private companion object {
        val DEFAULT_MATCHER = BraceMatchingUtil.getBraceMatcher(
            UnknownFileType.INSTANCE,
            Language.ANY,
        )
    }
}

/** One effective platform matcher, capability identity, and normalized pair rules. */
internal class BraceLanguageDefinition(
    val matcher: BraceMatcher,
    private val topology: BracePairTopology?,
    val capabilityId: String,
) : PairingRules<BraceOccurrence> {
    override fun isPair(
        openToken: BraceOccurrence,
        closeToken: BraceOccurrence,
    ): Boolean = openToken.structural == closeToken.structural &&
        matcher.isPairBraces(openToken.type, closeToken.type)

    override fun isStructuralPair(
        openToken: BraceOccurrence,
        closeToken: BraceOccurrence,
    ): Boolean = openToken.structural && closeToken.structural

    fun isPureSymmetric(tokenType: IElementType): Boolean =
        topology?.isPureSymmetric(tokenType)
            ?: (matcher.getOppositeBraceTokenType(tokenType) === tokenType)
}

/** Token identity plus the matcher's occurrence-specific structural classification. */
internal data class BraceOccurrence(
    val type: IElementType,
    val structural: Boolean,
)
