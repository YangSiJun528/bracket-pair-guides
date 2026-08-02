package com.sijunyang.bracketpairguides.analyzer

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeExtension
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.tree.IElementType
import java.util.Locale

data class BracketPair(
    val openOffset: Int,
    val openTokenLength: Int,
    val closeOffset: Int,
    val closeTokenLength: Int,
    val depth: Int,
    val openLine: Int,
    val closeLine: Int,
)

/**
 * Reads the editor's already-tokenized stream and applies the brace definitions
 * supplied by each language's [com.intellij.lang.PairedBraceMatcher] or legacy
 * file-type [BraceMatcher].
 *
 * This deliberately does not inspect raw characters. Comments, strings, layered
 * tokens, and language-specific bracket definitions remain the responsibility of
 * the current editor highlighter and language plugin. Separate PSI injections are
 * analyzed when the platform supplies their injected editor/document.
 */
class BracketPairAnalyzer(
    private val editor: Editor,
    private val fileType: FileType,
) : BracketPairProvider {
    constructor(editor: Editor) : this(
        editor = editor,
        fileType = FileDocumentManager.getInstance().getFile(editor.document)?.fileType
            ?: PlainTextFileType.INSTANCE,
    )

    override fun collect(progress: ProgressIndicator): List<BracketPair> {
        val document = editor.document
        if (document.textLength == 0) return emptyList()

        val collector = BracketStack<IElementType, Language>()
        val contextualCollector =
            ContextualBracketStack<IElementType, ContextualGroup>()
        val tokenSets = HashMap<Language, BraceTokenRules>()
        val contextualMatchers = HashMap<Language, ResolvedFileTypeMatcher?>()
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
                val tokens = tokenSets.getOrPut(language) {
                    LanguageBraceMatching.INSTANCE
                        .forLanguage(language)
                        ?.pairs
                        ?.let(::BraceTokenRules)
                        ?: BraceTokenRules.EMPTY
                }

                if (!tokens.isEmpty) {
                    val expectedCloses = tokens.expectedCloses(tokenType)
                    val isClose = tokens.isClose(tokenType)
                    if (tokens.isPureSymmetric(tokenType)) {
                        val symmetricClose = checkNotNull(expectedCloses)
                        val match = collector.close(language, tokenType, checkCanceled)
                        if (match == null) {
                            collector.open(
                                group = language,
                                expectedCloses = symmetricClose,
                                offset = iterator.start,
                                tokenLength = iterator.end - iterator.start,
                                line = document.getLineNumber(iterator.start),
                            )
                        } else {
                            result += match.toPair(
                                closeOffset = iterator.start,
                                closeTokenLength = iterator.end - iterator.start,
                                closeLine = document.getLineNumber(iterator.start),
                            )
                        }
                    } else if (expectedCloses != null) {
                        collector.open(
                            group = language,
                            expectedCloses = expectedCloses,
                            offset = iterator.start,
                            tokenLength = iterator.end - iterator.start,
                            line = document.getLineNumber(iterator.start),
                        )
                    } else if (isClose) {
                        collector.close(language, tokenType, checkCanceled)?.let { match ->
                            result += match.toPair(
                                closeOffset = iterator.start,
                                closeTokenLength = iterator.end - iterator.start,
                                closeLine = document.getLineNumber(iterator.start),
                            )
                        }
                    }
                } else {
                    val resolvedMatcher = if (contextualMatchers.containsKey(language)) {
                        contextualMatchers[language]
                    } else {
                        findFileTypeMatcher(language).also { resolved ->
                            contextualMatchers[language] = resolved
                        }
                    }
                    if (resolvedMatcher != null) {
                        val matcher = resolvedMatcher.matcher
                        val isLeft = matcher.isLBraceToken(iterator, text, fileType)
                        val isPureSymmetric =
                            isLeft && resolvedMatcher.isPureSymmetric(tokenType)
                        val isRight = (!isLeft || isPureSymmetric) &&
                            matcher.isRBraceToken(iterator, text, fileType)

                        if (isPureSymmetric && isRight) {
                            val context = matcher.contextAt(iterator, text)
                            val group = ContextualGroup(
                                matcherClass = matcher.javaClass,
                                tokenGroup = matcher.getBraceTokenGroupId(tokenType),
                            )
                            val match = contextualCollector.close(
                                group = group,
                                token = tokenType,
                                context = context.value,
                                strictContext = context.strict,
                                isPair = matcher::isPairBraces,
                                checkCanceled = checkCanceled,
                            )
                            if (match == null) {
                                contextualCollector.open(
                                    group = group,
                                    token = tokenType,
                                    context = context.value,
                                    offset = iterator.start,
                                    tokenLength = iterator.end - iterator.start,
                                    line = document.getLineNumber(iterator.start),
                                )
                            } else {
                                result += match.toPair(
                                    closeOffset = iterator.start,
                                    closeTokenLength = iterator.end - iterator.start,
                                    closeLine = document.getLineNumber(iterator.start),
                                )
                            }
                        } else if (isLeft) {
                            val context = matcher.contextAt(iterator, text)
                            contextualCollector.open(
                                group = ContextualGroup(
                                    matcherClass = matcher.javaClass,
                                    tokenGroup = matcher.getBraceTokenGroupId(tokenType),
                                ),
                                token = tokenType,
                                context = context.value,
                                offset = iterator.start,
                                tokenLength = iterator.end - iterator.start,
                                line = document.getLineNumber(iterator.start),
                            )
                        } else if (isRight) {
                            val context = matcher.contextAt(iterator, text)
                            contextualCollector.close(
                                group = ContextualGroup(
                                    matcherClass = matcher.javaClass,
                                    tokenGroup = matcher.getBraceTokenGroupId(tokenType),
                                ),
                                token = tokenType,
                                context = context.value,
                                strictContext = context.strict,
                                isPair = matcher::isPairBraces,
                                checkCanceled = checkCanceled,
                            )?.let { match ->
                                result += match.toPair(
                                    closeOffset = iterator.start,
                                    closeTokenLength = iterator.end - iterator.start,
                                    closeLine = document.getLineNumber(iterator.start),
                                )
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

    private fun findFileTypeMatcher(language: Language): ResolvedFileTypeMatcher? {
        FILE_TYPE_MATCHERS.forFileType(fileType)?.let {
            return ResolvedFileTypeMatcher(it)
        }
        val associatedFileType = language.associatedFileType
        return if (associatedFileType != null && associatedFileType !== fileType) {
            FILE_TYPE_MATCHERS.forFileType(associatedFileType)
                ?.let(::ResolvedFileTypeMatcher)
        } else {
            null
        }
    }

    private fun BracketStack.Match<IElementType>.toPair(
        closeOffset: Int,
        closeTokenLength: Int,
        closeLine: Int,
    ): BracketPair {
        return BracketPair(
            openOffset = open.offset,
            openTokenLength = open.tokenLength,
            closeOffset = closeOffset,
            closeTokenLength = closeTokenLength,
            depth = open.depth,
            openLine = open.line,
            closeLine = closeLine,
        )
    }

    private fun ContextualBracketStack.Match<IElementType>.toPair(
        closeOffset: Int,
        closeTokenLength: Int,
        closeLine: Int,
    ): BracketPair {
        return BracketPair(
            openOffset = open.offset,
            openTokenLength = open.tokenLength,
            closeOffset = closeOffset,
            closeTokenLength = closeTokenLength,
            depth = open.depth,
            openLine = open.line,
            closeLine = closeLine,
        )
    }

    private fun BraceMatcher.contextAt(
        iterator: com.intellij.openapi.editor.highlighter.HighlighterIterator,
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

    private data class ContextualGroup(
        val matcherClass: Class<out BraceMatcher>,
        val tokenGroup: Int,
    )

    private class ResolvedFileTypeMatcher(val matcher: BraceMatcher) {
        private val pairRules =
            (matcher as? PairedBraceMatcher)?.pairs?.let(::BraceTokenRules)

        fun isPureSymmetric(tokenType: IElementType): Boolean {
            pairRules?.let { return it.isPureSymmetric(tokenType) }
            return matcher.getOppositeBraceTokenType(tokenType) == tokenType &&
                matcher.isPairBraces(tokenType, tokenType)
        }
    }

    private data class TokenContext(
        val strict: Boolean,
        val value: String?,
    ) {
        companion object {
            val NONE = TokenContext(strict = false, value = null)
        }
    }

    private companion object {
        val FILE_TYPE_MATCHERS =
            FileTypeExtension<BraceMatcher>(BraceMatcher.EP_NAME.name)

        const val CANCELLATION_MASK = 0xFF
    }
}
