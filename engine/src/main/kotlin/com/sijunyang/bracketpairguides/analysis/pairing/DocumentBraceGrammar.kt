package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.UserFileType
import com.intellij.psi.CustomHighlighterTokenType
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairSink
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole
import java.util.Locale

/**
 * Shared token-stream pairing semantics for full analysis and bounded active lookup.
 *
 * Brace-language definitions and the language-family gate are evaluated for every token.
 * Each [Session] owns independent stack state, while matcher resolution is cached
 * by this core so repeated bounded sessions do not repeat extension lookup.
 */
internal class DocumentBraceGrammar(
    private val document: Document,
    private val fileType: FileType,
    private val text: CharSequence,
    private val languages: BraceLanguageCatalog,
    private val isLanguageEnabled: (String) -> Boolean,
) {
    private val definitions = HashMap<Language, BraceLanguageDefinition?>()

    enum class OpeningKind {
        DIRECTIONAL,
        SYMMETRIC_TOGGLE,
    }

    fun openingKind(iterator: HighlighterIterator): OpeningKind? {
        val token = classify(iterator) ?: return null
        return if (token.role == BracketRole.TOGGLE) {
            OpeningKind.SYMMETRIC_TOGGLE
        } else if (token.role == BracketRole.OPEN) {
            OpeningKind.DIRECTIONAL
        } else {
            null
        }
    }

    fun newSession(
        checkCanceled: () -> Unit,
        trackedOpenOffset: Int? = null,
        pairSink: PairSink? = null,
    ): Session = Session(checkCanceled, trackedOpenOffset, pairSink)

    inner class Session(
        checkCanceled: () -> Unit,
        trackedOpenOffset: Int?,
        private val pairSink: PairSink?,
    ) {
        private var emittedPair: BracketPair? = null
        private val pairing = PairingMachine<IElementType, BraceGroup> { group ->
            group.definition
        }.newSession(
            PairSink { openOffset, openLength, closeOffset, closeLength, depth, openLine, closeLine ->
                val target = pairSink
                if (target != null) {
                    target.accept(
                        openOffset,
                        openLength,
                        closeOffset,
                        closeLength,
                        depth,
                        openLine,
                        closeLine,
                    )
                } else {
                    emittedPair = BracketPair(
                        openOffset = openOffset,
                        openTokenLength = openLength,
                        closeOffset = closeOffset,
                        closeTokenLength = closeLength,
                        depth = depth,
                        openLine = openLine,
                        closeLine = closeLine,
                    )
                }
            },
            CancellationProbe(checkCanceled),
            trackedOpenOffset,
        )

        /** A structural close may depend on an opener before this bounded replay. */
        val requiresEarlierStructuralContext: Boolean
            get() = pairing.requiresEarlierStructuralContext()

        fun accept(iterator: HighlighterIterator): BracketPair? {
            val token = classify(iterator) ?: return null
            emittedPair = null
            val offset = iterator.start
            val tokenLength = iterator.end - iterator.start
            val line = document.getLineNumber(offset)
            pairing.accept(
                token.group,
                token.type,
                token.context.value,
                token.context.strict,
                token.role,
                token.structuralRole,
                offset,
                tokenLength,
                line,
            )
            return emittedPair
        }

        fun hasOpenAt(offset: Int): Boolean = pairing.hasOpenAt(offset)
    }

    private fun classify(iterator: HighlighterIterator): ClassifiedToken? {
        val tokenType = iterator.tokenType ?: return null
        val language = matcherLanguage(tokenType)
        val definition = definitions.cached(language) ?: return null
        val matcher = definition.matcher
        val isLeft = matcher.isLBraceToken(iterator, text, fileType)
        val isSymmetric = isLeft && definition.isPureSymmetric(tokenType)
        val isRight = (!isLeft || isSymmetric) &&
            matcher.isRBraceToken(iterator, text, fileType)
        if (!isLeft && !isRight) return null
        val role = bracketRole(isLeft, isRight, isSymmetric)

        return ClassifiedToken(
            type = tokenType,
            group = BraceGroup(
                language = language,
                tokenGroup = matcher.getBraceTokenGroupId(tokenType),
                definition = definition,
            ),
            context = matcher.contextAt(iterator),
            role = role,
            structuralRole = StructuralRole.of(
                definition.isStructuralOpen(tokenType),
                definition.isStructuralClose(tokenType),
            ),
        )
    }

    /** Custom syntax-table bracket tokens use the platform's TEXT matcher. */
    private fun matcherLanguage(tokenType: IElementType): Language = when {
        fileType !is UserFileType<*> -> tokenType.language
        tokenType.isCustomFileTypeBrace() -> PlainTextLanguage.INSTANCE
        else -> tokenType.language
    }

    private fun IElementType.isCustomFileTypeBrace(): Boolean = when (this) {
        CustomHighlighterTokenType.L_BRACE,
        CustomHighlighterTokenType.R_BRACE,
        CustomHighlighterTokenType.L_ANGLE,
        CustomHighlighterTokenType.R_ANGLE,
        CustomHighlighterTokenType.L_BRACKET,
        CustomHighlighterTokenType.R_BRACKET,
        CustomHighlighterTokenType.L_PARENTH,
        CustomHighlighterTokenType.R_PARENTH,
        -> true
        else -> false
    }

    private fun HashMap<Language, BraceLanguageDefinition?>.cached(
        language: Language,
    ): BraceLanguageDefinition? {
        if (containsKey(language)) return this[language]
        val candidate = languages.definitionFor(language)
        val definition = candidate?.takeIf { braceLanguage ->
            isLanguageEnabled(braceLanguage.capabilityId)
        }
        return definition.also { this[language] = it }
    }

    private fun BraceMatcher.contextAt(
        iterator: HighlighterIterator,
    ): TokenContext {
        val xmlMatcher = this as? XmlAwareBraceMatcher ?: return TokenContext.NONE
        val tokenType = iterator.tokenType ?: return TokenContext.NONE
        val group = getBraceTokenGroupId(tokenType)
        if (!xmlMatcher.isStrictTagMatching(fileType, group)) return TokenContext.NONE

        val caseSensitive = xmlMatcher.areTagsCaseSensitive(fileType, group)
        val tagName = xmlMatcher.getTagName(text, iterator)?.let { name ->
            if (caseSensitive) name else name.lowercase(Locale.ROOT)
        }
        return TokenContext(strict = true, value = tagName)
    }

    private data class ClassifiedToken(
        val type: IElementType,
        val group: BraceGroup,
        val context: TokenContext,
        val role: BracketRole,
        val structuralRole: StructuralRole,
    )

    /** Equality intentionally preserves the original language + numeric group key. */
    private class BraceGroup(
        val language: Language,
        val tokenGroup: Int,
        val definition: BraceLanguageDefinition,
    ) {
        override fun equals(other: Any?): Boolean =
            other is BraceGroup && language == other.language && tokenGroup == other.tokenGroup

        override fun hashCode(): Int = 31 * language.hashCode() + tokenGroup
    }

    private data class TokenContext(
        val strict: Boolean,
        val value: String?,
    ) {
        companion object {
            val NONE = TokenContext(strict = false, value = null)
        }
    }
}

internal fun bracketRole(
    isLeft: Boolean,
    isRight: Boolean,
    isPureSymmetric: Boolean,
): BracketRole {
    require(isLeft || isRight) { "A bracket token must have at least one direction" }
    return when {
        isPureSymmetric && isRight -> BracketRole.TOGGLE
        isLeft -> BracketRole.OPEN
        else -> BracketRole.CLOSE
    }
}
