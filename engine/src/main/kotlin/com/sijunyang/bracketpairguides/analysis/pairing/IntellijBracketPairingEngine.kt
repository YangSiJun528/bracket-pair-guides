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
import java.util.Locale

/**
 * Shared token-stream pairing semantics for full analysis and bounded active lookup.
 *
 * Matcher resolution and the language-family gate are evaluated for every token.
 * Each [Session] owns independent stack state, while matcher resolution is cached
 * by this core so repeated bounded sessions do not repeat extension lookup.
 */
internal class IntellijBracketPairingEngine(
    private val document: Document,
    private val fileType: FileType,
    private val text: CharSequence,
    private val isLanguageEnabled: (String) -> Boolean = { true },
) {
    private val matchers = HashMap<Language, ResolvedLanguageBraceMatcher?>()

    public enum class OpeningKind {
        DIRECTIONAL,
        SYMMETRIC_TOGGLE,
    }

    public fun openingKind(iterator: HighlighterIterator): OpeningKind? {
        val token = classify(iterator)?.takeIf { it.isLeft } ?: return null
        return if (token.isSymmetric && token.isRight) {
            OpeningKind.SYMMETRIC_TOGGLE
        } else {
            OpeningKind.DIRECTIONAL
        }
    }

    public fun newSession(
        checkCanceled: () -> Unit = {},
        trackedOpenOffset: Int? = null,
    ): Session = Session(checkCanceled, trackedOpenOffset)

    public inner class Session(
        private val checkCanceled: () -> Unit,
        private val trackedOpenOffset: Int?,
    ) {
        private val collector = BraceMatcherStack<IElementType, MatcherGroup>(trackedOpenOffset)
        private var trackedGroup: MatcherGroup? = null

        /** A structural close may depend on an opener before this bounded replay. */
        public var requiresEarlierStructuralContext: Boolean = false
            private set

        public fun accept(iterator: HighlighterIterator): BracketPair? {
            val token = classify(iterator) ?: return null
            val offset = iterator.start
            val tokenLength = iterator.end - iterator.start
            val line = document.getLineNumber(offset)

            if (token.isSymmetric && token.isRight) {
                val match = close(token)
                if (match == null) {
                    open(token, offset, tokenLength, line)
                    return null
                }
                return match.toPair(offset, tokenLength, line)
            }

            if (token.isLeft) {
                open(token, offset, tokenLength, line)
                return null
            }

            if (!token.isRight) return null
            return close(token)?.toPair(offset, tokenLength, line)
        }

        public fun hasOpenAt(offset: Int): Boolean = collector.containsOpenAt(offset)

        private fun open(
            token: ClassifiedToken,
            offset: Int,
            tokenLength: Int,
            line: Int,
        ) {
            collector.open(
                group = token.group,
                token = token.type,
                context = token.context.value,
                strictContext = token.context.strict,
                offset = offset,
                tokenLength = tokenLength,
                line = line,
                structural = token.resolved.isStructuralOpen(token.type),
            )
            if (offset == trackedOpenOffset) trackedGroup = token.group
        }

        private fun close(
            token: ClassifiedToken,
        ): BraceMatcherStack.Match<IElementType>? {
            val match = collector.close(
                group = token.group,
                token = token.type,
                context = token.context.value,
                strictContext = token.context.strict,
                isPair = token.resolved.isPair,
                isStructuralPair = token.resolved.isStructuralPair,
                checkCanceled = checkCanceled,
                canCloseStructural = token.resolved.isStructuralClose(token.type),
            )
            if (trackedGroup == token.group &&
                token.resolved.isStructuralClose(token.type) &&
                (match == null ||
                    !token.resolved.isStructuralPair(match.open.token, token.type))
            ) {
                requiresEarlierStructuralContext = true
            }
            return match
        }
    }

    private fun classify(iterator: HighlighterIterator): ClassifiedToken? {
        val tokenType = iterator.tokenType ?: return null
        val language = matcherLanguage(tokenType)
        val resolved = matchers.cached(language) ?: return null
        val matcher = resolved.matcher
        val isLeft = matcher.isLBraceToken(iterator, text, fileType)
        val isSymmetric = isLeft && resolved.isPureSymmetric(tokenType)
        val isRight = (!isLeft || isSymmetric) &&
            matcher.isRBraceToken(iterator, text, fileType)
        if (!isLeft && !isRight) return null

        return ClassifiedToken(
            type = tokenType,
            resolved = resolved,
            group = MatcherGroup(
                language = language,
                tokenGroup = matcher.getBraceTokenGroupId(tokenType),
            ),
            context = matcher.contextAt(iterator),
            isLeft = isLeft,
            isRight = isRight,
            isSymmetric = isSymmetric,
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

    private fun HashMap<Language, ResolvedLanguageBraceMatcher?>.cached(
        language: Language,
    ): ResolvedLanguageBraceMatcher? {
        if (containsKey(language)) return this[language]
        val candidate = LanguageBraceMatchers.resolve(language)
        val resolved = candidate?.takeIf { matcher ->
            isLanguageEnabled(matcher.capabilityId)
        }
        return resolved.also { this[language] = it }
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

    private fun BraceMatcherStack.Match<IElementType>.toPair(
        closeOffset: Int,
        closeTokenLength: Int,
        closeLine: Int,
    ): BracketPair = BracketPair(
        openOffset = open.offset,
        openTokenLength = open.tokenLength,
        closeOffset = closeOffset,
        closeTokenLength = closeTokenLength,
        depth = open.depth,
        openLine = open.line,
        closeLine = closeLine,
    )

    private data class ClassifiedToken(
        val type: IElementType,
        val resolved: ResolvedLanguageBraceMatcher,
        val group: MatcherGroup,
        val context: TokenContext,
        val isLeft: Boolean,
        val isRight: Boolean,
        val isSymmetric: Boolean,
    )

    private data class MatcherGroup(
        val language: Language,
        val tokenGroup: Int,
    )

    private data class TokenContext(
        val strict: Boolean,
        val value: String?,
    ) {
        companion object {
            val NONE = TokenContext(strict = false, value = null)
        }
    }
}
