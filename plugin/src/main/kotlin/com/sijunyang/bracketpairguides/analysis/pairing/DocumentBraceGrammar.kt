package com.sijunyang.bracketpairguides.analysis.pairing

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
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairSink
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole
import java.util.Locale

/**
 * Token-stream pairing semantics for one full document analysis.
 *
 * Effective matcher definitions and the language-family gate are evaluated for every
 * token. Each [Session] owns independent stack state, while definitions are cached by
 * token language so one full scan resolves each platform matcher only once.
 */
internal class DocumentBraceGrammar(
    private val document: Document,
    private val fileType: FileType,
    private val text: CharSequence,
    private val languages: BraceLanguageCatalog,
    private val isLanguageEnabled: (String) -> Boolean,
) {
    private val definitions = HashMap<Language, BraceLanguageDefinition?>()
    private var inspectedLanguage = false
    private var foundCompatibleMatcher = false
    private var foundEnabledMatcher = false

    fun matcherAvailability(): BraceMatcherAvailability = when {
        foundEnabledMatcher -> BraceMatcherAvailability.AVAILABLE
        foundCompatibleMatcher -> BraceMatcherAvailability.DISABLED
        inspectedLanguage -> BraceMatcherAvailability.UNAVAILABLE
        else -> BraceMatcherAvailability.UNDETERMINED
    }

    fun newSession(checkCanceled: () -> Unit, pairSink: PairSink, maximumPendingOpens: Int): Session =
        Session(checkCanceled, pairSink, maximumPendingOpens)

    inner class Session(checkCanceled: () -> Unit, pairSink: PairSink, maximumPendingOpens: Int) {
        private val pairing =
            PairingMachine<IElementType, BraceGroup> { group ->
                group.definition
            }.newSession(
                pairSink,
                CancellationProbe(checkCanceled),
                maximumPendingOpens,
            )

        /** Returns false before an opener would cross the pending-open capacity. */
        fun accept(iterator: HighlighterIterator): Boolean {
            val token = classify(iterator) ?: return true
            val offset = iterator.start
            val tokenLength = iterator.end - iterator.start
            val line = document.getLineNumber(offset)
            return pairing.accept(
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
        }
    }

    private fun classify(iterator: HighlighterIterator): ClassifiedToken? {
        val tokenType = iterator.tokenType ?: return null
        val language = matcherLanguage(tokenType)
        val definition = definitions.cached(language, iterator) ?: return null
        val matcher = definition.matcher
        val isLeft = matcher.isLBraceToken(iterator, text, fileType)
        val isSymmetric = isLeft && definition.isPureSymmetric(tokenType)
        val isRight =
            (!isLeft || isSymmetric) &&
                matcher.isRBraceToken(iterator, text, fileType)
        if (!isLeft && !isRight) return null
        val role = bracketRole(isLeft, isRight, isSymmetric)

        return ClassifiedToken(
            type = tokenType,
            group =
            BraceGroup(
                language = language,
                tokenGroup = matcher.getBraceTokenGroupId(tokenType),
                definition = definition,
            ),
            context = matcher.contextAt(iterator),
            role = role,
            structuralRole =
            definition.structuralRole(
                iterator = iterator,
                text = text,
                fileType = fileType,
                isLeft = isLeft,
                isRight = isRight,
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
        iterator: HighlighterIterator,
    ): BraceLanguageDefinition? {
        if (containsKey(language)) return this[language]
        inspectedLanguage = true
        val candidate = languages.definitionFor(fileType, iterator, language)
        if (candidate != null) foundCompatibleMatcher = true
        val definition =
            candidate?.takeIf { braceLanguage ->
                isLanguageEnabled(braceLanguage.capabilityId)
            }
        if (definition != null) foundEnabledMatcher = true
        return definition.also { this[language] = it }
    }

    private fun BraceMatcher.contextAt(iterator: HighlighterIterator): TokenContext {
        val xmlMatcher = this as? XmlAwareBraceMatcher ?: return TokenContext.NONE
        val tokenType = iterator.tokenType ?: return TokenContext.NONE
        val group = getBraceTokenGroupId(tokenType)
        if (!xmlMatcher.isStrictTagMatching(fileType, group)) return TokenContext.NONE

        val caseSensitive = xmlMatcher.areTagsCaseSensitive(fileType, group)
        val tagName =
            xmlMatcher.getTagName(text, iterator)?.let { name ->
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
    private class BraceGroup(val language: Language, val tokenGroup: Int, val definition: BraceLanguageDefinition) {
        override fun equals(other: Any?): Boolean =
            other is BraceGroup && language == other.language && tokenGroup == other.tokenGroup

        override fun hashCode(): Int = 31 * language.hashCode() + tokenGroup
    }

    private data class TokenContext(val strict: Boolean, val value: String?) {
        companion object {
            val NONE = TokenContext(strict = false, value = null)
        }
    }
}

internal fun bracketRole(isLeft: Boolean, isRight: Boolean, isPureSymmetric: Boolean): BracketRole {
    require(isLeft || isRight) { "A bracket token must have at least one direction" }
    return when {
        isPureSymmetric && isRight -> BracketRole.TOGGLE
        isLeft -> BracketRole.OPEN
        else -> BracketRole.CLOSE
    }
}
