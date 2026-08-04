package com.sijunyang.bracketpairguides.analyzer

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.tree.IElementType
import java.util.Locale

internal data class BracketPair(
    val openOffset: Int,
    val openTokenLength: Int,
    val closeOffset: Int,
    val closeTokenLength: Int,
    val depth: Int,
    val openLine: Int,
    val closeLine: Int,
)

/**
 * Pairs tokens recognized by each token language's `lang.braceMatcher`.
 *
 * Recognition stays on the editor's token stream. The analyzer neither scans
 * raw characters nor falls back to the legacy file-type brace matcher. A
 * language without a registered matcher is therefore deliberately ignored.
 */
internal class BracketPairAnalyzer(
    private val editor: Editor,
    private val fileType: FileType,
    private val isLanguageEnabled: (String) -> Boolean = { true },
) : BracketPairProvider {
    constructor(editor: Editor) : this(
        editor = editor,
        fileType = FileDocumentManager.getInstance().getFile(editor.document)?.fileType
            ?: PlainTextFileType.INSTANCE,
    )

    constructor(
        editor: Editor,
        isLanguageEnabled: (String) -> Boolean,
    ) : this(
        editor = editor,
        fileType = FileDocumentManager.getInstance().getFile(editor.document)?.fileType
            ?: PlainTextFileType.INSTANCE,
        isLanguageEnabled = isLanguageEnabled,
    )

    override fun collect(progress: ProgressIndicator): List<BracketPair> {
        val document = editor.document
        if (document.textLength == 0) return emptyList()

        val collector = BraceMatcherStack<IElementType, MatcherGroup>()
        val matchers = HashMap<Language, ResolvedLanguageBraceMatcher?>()
        val result = ArrayList<BracketPair>()
        val iterator = editor.highlighter.createIterator(0)
        if (iterator.document !== document) return emptyList()
        val text = document.immutableCharSequence
        val checkCanceled = progress::checkCanceled
        var visitedTokens = 0

        while (!iterator.atEnd()) {
            if (visitedTokens++ and CANCELLATION_MASK == 0) {
                progress.checkCanceled()
            }

            val tokenType = iterator.tokenType
            if (tokenType != null) {
                val language = tokenType.language
                val resolved = matchers.cached(language)
                if (resolved != null) {
                    val matcher = resolved.matcher
                    val isLeft = matcher.isLBraceToken(iterator, text, fileType)
                    val isSymmetric = isLeft && resolved.isPureSymmetric(tokenType)
                    val isRight = (!isLeft || isSymmetric) &&
                        matcher.isRBraceToken(iterator, text, fileType)

                    if (isLeft || isRight) {
                        val group = MatcherGroup(
                            language = language,
                            tokenGroup = matcher.getBraceTokenGroupId(tokenType),
                        )
                        val context = matcher.contextAt(iterator, text)
                        val closeOffset = iterator.start
                        val closeTokenLength = iterator.end - iterator.start
                        val line = document.getLineNumber(iterator.start)

                        if (isSymmetric && isRight) {
                            val match = collector.close(
                                group = group,
                                token = tokenType,
                                context = context.value,
                                strictContext = context.strict,
                                isPair = resolved.isPair,
                                checkCanceled = checkCanceled,
                            )
                            if (match == null) {
                                collector.open(
                                    group = group,
                                    token = tokenType,
                                    context = context.value,
                                    strictContext = context.strict,
                                    offset = closeOffset,
                                    tokenLength = closeTokenLength,
                                    line = line,
                                )
                            } else {
                                result += match.toPair(closeOffset, closeTokenLength, line)
                            }
                        } else if (isLeft) {
                            collector.open(
                                group = group,
                                token = tokenType,
                                context = context.value,
                                strictContext = context.strict,
                                offset = closeOffset,
                                tokenLength = closeTokenLength,
                                line = line,
                            )
                        } else if (isRight) {
                            collector.close(
                                group = group,
                                token = tokenType,
                                context = context.value,
                                strictContext = context.strict,
                                isPair = resolved.isPair,
                                checkCanceled = checkCanceled,
                            )?.let { match ->
                                result += match.toPair(closeOffset, closeTokenLength, line)
                            }
                        }
                    }
                }
            }
            iterator.advance()
        }

        progress.checkCanceled()
        result.sortWith(compareBy(BracketPair::openOffset, BracketPair::closeOffset))
        return result
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

    private fun BraceMatcher.contextAt(
        iterator: HighlighterIterator,
        text: CharSequence,
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

    private companion object {
        const val CANCELLATION_MASK = 0xFF
    }
}
