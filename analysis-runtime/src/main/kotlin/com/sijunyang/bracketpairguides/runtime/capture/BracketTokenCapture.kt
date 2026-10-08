package com.sijunyang.bracketpairguides.runtime.capture

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.runtime.capture.matcher.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.runtime.capture.matcher.BraceLanguageDefinition
import com.sijunyang.bracketpairguides.runtime.capture.matcher.DocumentBraceGrammar
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Read-access adapter for one revision's bounded token chunks and demanded compatibility answers.
 * The caller owns read-action scheduling, cancellation, revision validation, and session lifetime.
 * Keep an exclusive sequential owner, with a happens-before handoff when coroutines change threads.
 * Neither iterators nor platform identities escape returned chunks.
 */
internal class BracketTokenCapture(
    private val input: EditorSource,
    private val languages: BraceLanguageCatalog = BraceLanguageCatalog(),
) {
    private var grammar: DocumentBraceGrammar? = null
    private val token = DocumentBraceGrammar.Classification()
    private var previousGroup: TokenGroup? = null
    private var previousCapturedTokens = 0
    private var previousVisitedTokens = 0
    private val languageOrdinals = HashMap<Language, Int>()
    private val definitions = ArrayList<BraceLanguageDefinition>()
    private val kindsByType = LinkedHashMap<IElementType, TokenKind>(16, 0.75f, true)
    private val typesByKind = WeakHashMap<TokenKind, IElementType>()

    fun capture(
        offset: Int,
        maximumVisitedTokens: Int = DEFAULT_VISITED_TOKENS,
        checkCanceled: () -> Unit,
    ): TokenBatch {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        require(maximumVisitedTokens > 0) { "A capture chunk must visit at least one token" }
        checkCanceled()
        val document = input.editor.document
        require(offset in 0..document.textLength) { "Capture offset must be inside this revision" }
        if (offset == document.textLength) return TokenBatch.capture { ending(offset, true, 0) }

        val iterator = input.editor.highlighter.createIterator(offset)
        if (iterator.document !==
            document
        ) {
            return TokenBatch.capture { ending(document.textLength, true, 0, sourceAvailable = false) }
        }
        check(iterator.atEnd() || iterator.start == offset) { "Capture must resume at an exact token start" }
        val classifier = grammar ?: DocumentBraceGrammar(
            fileType = input.fileType,
            text = document.charsSequence,
            languages = languages,
            disabledLanguageIds = input.disabledLanguageIds,
        ).also { grammar = it }
        return TokenBatch.capture(initialCapacity(maximumVisitedTokens)) { tokens ->
            var visited = 0
            while (!iterator.atEnd() && visited < maximumVisitedTokens) {
                if (visited and CANCELLATION_MASK == 0) checkCanceled()
                visited++
                if (classifier.classifyInto(iterator, token)) {
                    val ordinal = languageOrdinals.getOrPut(token.language) {
                        definitions.add(token.definition)
                        definitions.lastIndex
                    }
                    tokens.append(
                        kind = kindFor(token.type),
                        group = groupFor(ordinal, token.tokenGroup),
                        context = token.context,
                        strictContext = token.strictContext,
                        role = token.role,
                        structuralRole = token.structuralRole,
                        offset = iterator.start,
                        tokenLength = iterator.end - iterator.start,
                        line = document.getLineNumber(iterator.start),
                    )
                }
                iterator.advance()
            }
            checkCanceled()
            val end = iterator.atEnd()
            ending(if (end) document.textLength else iterator.start, end, visited)
        }.also {
            previousCapturedTokens = it.size
            previousVisitedTokens = it.visitedTokens
        }
    }

    private fun initialCapacity(maximumVisitedTokens: Int): Int {
        val estimate = if (previousVisitedTokens == 0) {
            INITIAL_CAPTURED_TOKENS
        } else {
            // Round up the previous successful chunk's density, scaling for variable visit limits.
            (
                (previousCapturedTokens.toLong() * maximumVisitedTokens + previousVisitedTokens - 1L) /
                    previousVisitedTokens
                ).coerceIn(MINIMUM_CAPTURED_TOKENS.toLong(), DEFAULT_VISITED_TOKENS.toLong()).toInt()
        }
        // Power-of-two seeds keep dense growth aligned with the capture visit bound.
        var capacity = 1
        while (capacity < estimate) capacity *= 2
        return minOf(capacity, maximumVisitedTokens, DEFAULT_VISITED_TOKENS)
    }

    fun matches(openKind: TokenKind, closeKind: TokenKind, group: TokenGroup): Boolean {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val open = checkNotNull(typesByKind[openKind]) { "Opening token kind belongs to another capture" }
        val close = checkNotNull(typesByKind[closeKind]) { "Closing token kind belongs to another capture" }
        return definitions[group.languageOrdinal].isPair(open, close)
    }

    private fun kindFor(type: IElementType): TokenKind {
        kindsByType[type]?.let { return it }
        val kind = TokenKind()
        kindsByType[type] = kind
        typesByKind[kind] = type
        if (kindsByType.size > MAXIMUM_CACHED_KINDS) {
            val oldest = kindsByType.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return kind
    }

    private fun groupFor(language: Int, numericGroup: Int): TokenGroup {
        previousGroup?.let { if (it.languageOrdinal == language && it.tokenGroup == numericGroup) return it }
        return TokenGroup(language, numericGroup).also { previousGroup = it }
    }

    private fun ending(
        nextOffset: Int,
        end: Boolean,
        visitedTokens: Int,
        sourceAvailable: Boolean = true,
    ): TokenBatch.End = TokenBatch.End(
        nextOffset,
        end,
        visitedTokens,
        if (sourceAvailable) {
            grammar?.matcherAvailability() ?: BraceMatcherAvailability.UNDETERMINED
        } else {
            BraceMatcherAvailability.UNDETERMINED
        },
        sourceAvailable,
    )

    private companion object {
        const val CANCELLATION_MASK = 0xFF
        const val DEFAULT_VISITED_TOKENS = 512
        const val INITIAL_CAPTURED_TOKENS = 32
        const val MINIMUM_CAPTURED_TOKENS = 8
        const val MAXIMUM_CACHED_KINDS = 1_024
    }
}
