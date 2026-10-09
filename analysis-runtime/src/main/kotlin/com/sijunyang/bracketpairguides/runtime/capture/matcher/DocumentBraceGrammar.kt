package com.sijunyang.bracketpairguides.runtime.capture.matcher

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher
import com.intellij.lang.Language
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.UserFileType
import com.intellij.psi.CustomHighlighterTokenType
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.core.input.StructuralRole
import com.sijunyang.bracketpairguides.core.input.TokenRole
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import java.util.Locale

/**
 * Platform token classification for one analysis revision.
 *
 * Matcher definitions and their enabled language families are cached by token language.
 * Role, XML context, and structural callbacks are evaluated for each occurrence into a
 * caller-owned reusable [Classification]. Capture and pairing state belong to the caller.
 */
internal class DocumentBraceGrammar(
    private val fileType: FileType,
    private val text: CharSequence,
    private val languages: BraceLanguageCatalog,
    private val disabledLanguageIds: Set<String>,
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

    /** Fields are valid only after true; the reusable carrier stays in the platform adapter. */
    fun classifyInto(iterator: HighlighterIterator, token: Classification): Boolean {
        val tokenType = iterator.tokenType ?: return false
        val language = matcherLanguage(tokenType)
        val definition = definitions.cached(language, iterator) ?: return false
        val matcher = definition.matcher
        val isLeft = matcher.isLBraceToken(iterator, text, fileType)
        val isSymmetric = isLeft && definition.isPureSymmetric(tokenType)
        val isRight =
            (!isLeft || isSymmetric) &&
                matcher.isRBraceToken(iterator, text, fileType)
        if (!isLeft && !isRight) return false
        val role = bracketRole(isLeft, isRight, isSymmetric)

        token.type = tokenType
        token.language = language
        token.definition = definition
        token.tokenGroup = matcher.getBraceTokenGroupId(tokenType)
        matcher.contextAt(iterator, token)
        token.role = role
        token.structuralRole = definition.structuralRole(
            iterator = iterator,
            text = text,
            fileType = fileType,
            isLeft = isLeft,
            isRight = isRight,
        )
        return true
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
                braceLanguage.capabilityId !in disabledLanguageIds
            }
        if (definition != null) foundEnabledMatcher = true
        return definition.also { this[language] = it }
    }

    private fun BraceMatcher.contextAt(iterator: HighlighterIterator, token: Classification) {
        // Every accepted token replaces both fields, including non-XML and non-strict tokens.
        token.strictContext = false
        token.context = null
        val xmlMatcher = this as? XmlAwareBraceMatcher ?: return
        val tokenType = iterator.tokenType ?: return
        val group = getBraceTokenGroupId(tokenType)
        if (!xmlMatcher.isStrictTagMatching(fileType, group)) return

        val caseSensitive = xmlMatcher.areTagsCaseSensitive(fileType, group)
        val tagName =
            xmlMatcher.getTagName(text, iterator)?.let { name ->
                if (caseSensitive) name else name.lowercase(Locale.ROOT)
            }
        token.strictContext = true
        token.context = tagName
    }

    /** One logical owner's scratch output; never publish this object or read it after false. */
    internal class Classification {
        lateinit var type: IElementType
        lateinit var language: Language
        lateinit var definition: BraceLanguageDefinition
        var tokenGroup: Int = 0
        var context: String? = null
        var strictContext: Boolean = false
        lateinit var role: TokenRole
        lateinit var structuralRole: StructuralRole
    }
}

internal fun bracketRole(isLeft: Boolean, isRight: Boolean, isPureSymmetric: Boolean): TokenRole {
    require(isLeft || isRight) { "A bracket token must have at least one direction" }
    return when {
        isPureSymmetric && isRight -> TokenRole.TOGGLE
        isLeft -> TokenRole.OPEN
        else -> TokenRole.CLOSE
    }
}
