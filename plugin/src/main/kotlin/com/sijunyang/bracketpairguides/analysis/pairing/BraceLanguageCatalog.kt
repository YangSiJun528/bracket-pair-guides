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
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole

/** Effective brace-matcher definitions and their shared capability families. */
internal class BraceLanguageCatalog {
    fun definitionFor(language: Language): BraceLanguageDefinition? {
        val pairedMatcher =
            LanguageBraceMatching.INSTANCE.forLanguage(language) ?: return null
        return pairedDefinition(language, pairedMatcher)
    }

    /** Resolves the same effective matcher used by the platform for this token. */
    fun definitionFor(
        fileType: FileType,
        iterator: HighlighterIterator,
        language: Language,
    ): BraceLanguageDefinition? {
        val pairedMatcher = LanguageBraceMatching.INSTANCE.forLanguage(language)
        if (pairedMatcher != null) {
            return pairedDefinition(language, pairedMatcher)
        }

        val matcher = BraceMatchingUtil.getBraceMatcher(fileType, iterator)
        if (matcher === DEFAULT_MATCHER) return null
        val topology = (matcher as? PairedBraceMatcher)?.let { declaredMatcher ->
            BracePairTopology(declaredMatcher.pairs)
        }
        return BraceLanguageDefinition(
            matcher = matcher,
            topology = topology,
            capabilityId = capabilityOwner(fileType, language).id,
        )
    }

    private fun pairedDefinition(
        language: Language,
        pairedMatcher: PairedBraceMatcher,
    ): BraceLanguageDefinition {
        val matcher = (pairedMatcher as? BraceMatcher)
            ?: PairedBraceMatcherAdapter(pairedMatcher, language)
        return BraceLanguageDefinition(
            matcher = matcher,
            topology = BracePairTopology(pairedMatcher.pairs),
            capabilityId = capabilityOwner(language, pairedMatcher).id,
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
) : PairingRules<IElementType> {
    override fun isPair(
        openToken: IElementType,
        closeToken: IElementType,
    ): Boolean = matcher.isPairBraces(openToken, closeToken)

    /** Uses declared pair topology when available; legacy matchers classify each occurrence. */
    fun structuralRole(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType,
        isLeft: Boolean,
        isRight: Boolean,
    ): StructuralRole {
        val tokenType = iterator.tokenType ?: return StructuralRole.NONE
        val declaredTopology = topology
        if (declaredTopology != null) {
            return StructuralRole.of(
                isLeft && declaredTopology.isStructuralOpen(tokenType),
                isRight && declaredTopology.isStructuralClose(tokenType),
            )
        }

        val isStructural = matcher.isStructuralBrace(iterator, text, fileType)
        return StructuralRole.of(
            isStructural && isLeft,
            isStructural && isRight,
        )
    }

    fun isPureSymmetric(tokenType: IElementType): Boolean =
        topology?.isPureSymmetric(tokenType)
            ?: (matcher.getOppositeBraceTokenType(tokenType) === tokenType)
}
