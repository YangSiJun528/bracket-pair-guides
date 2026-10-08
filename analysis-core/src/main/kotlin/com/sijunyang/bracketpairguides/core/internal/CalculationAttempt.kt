package com.sijunyang.bracketpairguides.core.internal

import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.DocumentFacts
import com.sijunyang.bracketpairguides.core.input.LineInput
import com.sijunyang.bracketpairguides.core.input.PrefixChunk
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import java.util.LinkedHashMap

/** One sequential capture/calculation attempt. No host object or mutable state crosses attempts. */
internal class CalculationAttempt(private val control: CalculationControl) {
    private val checkCanceled: () -> Unit = control::checkCanceled

    suspend fun analyze(input: BracketInput, coverage: AnalysisCoverage, cache: CalculationCache): AnalysisResult {
        val facts = input.beginAttempt()
        control.checkCanceled()
        cache.admit(facts.reuseRevision, checkCanceled)
        val recognition = if (coverage.pairs) {
            recognize(input, facts)
        } else {
            DocumentBracketRecognition.Complete(PairTable.empty(), BraceMatcherAvailability.UNDETERMINED)
        }
        val prepared = SnapshotCalculation.prepare(coverage, recognition, facts.length, facts.lineCount, checkCanceled)
        val guides = prepared.guideLines?.let { buildGuides(input, it, facts.tabSize, facts.length) }
        val calculated = prepared.finish(guides)
        input.validateCurrent()
        control.checkCanceled()
        return when (calculated) {
            is CalculatedAnalysis.Unavailable -> AnalysisResult.Unavailable(coverage, calculated.limit)

            is CalculatedAnalysis.Available -> {
                val indexes = cache.canonical(facts, calculated, checkCanceled)
                control.checkCanceled()
                AnalysisResult.Available(
                    IndexedBracketView(indexes),
                    calculated.coverage,
                    calculated.matcherAvailability,
                    calculated.limit,
                )
            }
        }
    }

    private suspend fun recognize(source: BracketInput, facts: DocumentFacts): DocumentBracketRecognition {
        val pairs = PairCollection(BracketRecognitionLimits.completedPairs)
        val pairing = PairingMachine<TokenKind, TokenGroup>().newSession(
            pairs,
            CancellationProbe(checkCanceled),
            BracketRecognitionLimits.MAXIMUM_PENDING_OPENS,
        )
        val answers = LinkedHashMap<PairingMachine.RuleRequest<TokenKind, TokenGroup>, Boolean>(16, 0.75f, true)
        var offset = 0
        try {
            while (true) {
                control.checkCanceled()
                val batch = source.tokensAt(offset)
                require(
                    batch.nextOffset in offset..facts.length &&
                        (if (batch.end) batch.nextOffset == facts.length else batch.nextOffset > offset),
                ) {
                    "Token capture must advance at an exact source boundary or end"
                }
                if (!batch.sourceAvailable) {
                    return DocumentBracketRecognition.Complete(PairTable.empty(), BraceMatcherAvailability.UNDETERMINED)
                }
                var previousTokenEnd = offset
                for (index in 0 until batch.size) {
                    if (index and 255 == 0) control.checkCanceled()
                    val tokenOffset = batch.offsetAt(index)
                    val tokenEnd = tokenOffset.toLong() + batch.lengthAt(index)
                    require(
                        tokenOffset >= previousTokenEnd && tokenEnd <= batch.nextOffset &&
                            batch.lineAt(index) < facts.lineCount,
                    )
                    previousTokenEnd = tokenEnd.toInt()
                    val flags = batch.flagsAt(index)
                    var step = pairing.begin(
                        batch.groupAt(index), batch.kindAt(index), batch.contextAt(index),
                        flags and 16 != 0, ROLES[flags and 3], STRUCTURAL[(flags ushr 2) and 3],
                        tokenOffset, batch.lengthAt(index), batch.lineAt(index),
                    )
                    while (step == PairingMachine.Step.NEEDS_RULE) {
                        val request = pairing.ruleRequest()
                        val answer =
                            answers[request]
                                ?: source.areCompatible(
                                    request.openToken(),
                                    request.closeToken(),
                                    request.group(),
                                ).also {
                                    answers[request] = it
                                    if (answers.size >
                                        2048
                                    ) {
                                        val oldest = answers.entries.iterator()
                                        oldest.next()
                                        oldest.remove()
                                    }
                                }
                        control.checkCanceled()
                        step = pairing.resume(answer)
                    }
                    if (step == PairingMachine.Step.PENDING_CAPACITY) {
                        return DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PENDING_OPEN_CAPACITY)
                    }
                }
                if (batch.end) {
                    return DocumentBracketRecognition.Complete(
                        checkNotNull(pairs.authoritativePairs()),
                        batch.matcherAvailability,
                    )
                }
                offset = batch.nextOffset
            }
        } catch (_: PairCapacityReached) {
            return DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PAIR_CAPACITY)
        }
    }

    private suspend fun buildGuides(
        input: LineInput,
        lines: IntRange,
        tabSize: Int,
        documentLength: Int,
    ): GuidePositionIndex {
        val builder = checkNotNull(GuidePositionIndex.builder(lines.first, lines.last - lines.first + 1, checkCanceled))
        var firstLine = lines.first
        while (firstLine <= lines.last) {
            control.checkCanceled()
            val count = minOf(128L, lines.last.toLong() - firstLine + 1).toInt()
            val batch = input.initialPrefixes(firstLine, count)
            require(batch.firstLine == firstLine && batch.prefixes.size == count)
            for ((relative, prefix) in batch.prefixes.withIndex()) {
                require(prefix.text.length <= 128 && prefix.lineEndOffset <= documentLength)
                val indentation = LineIndentation(tabSize, checkCanceled)
                var current = prefix
                indentation.append(current.text, current.endOfLine)
                while (!indentation.isComplete) {
                    control.checkCanceled()
                    current = continuation(input, firstLine + relative, current)
                    indentation.append(current.text, current.endOfLine)
                }
                builder.append(indentation.column)
            }
            firstLine += count
        }
        return builder.seal()
    }

    private suspend fun continuation(input: LineInput, line: Int, previous: PrefixChunk): PrefixChunk {
        val next = input.continuePrefix(line, previous.afterOffset)
        require(
            next.text.length <= 4096 && next.lineEndOffset == previous.lineEndOffset &&
                next.afterOffset > previous.afterOffset && next.afterOffset - previous.afterOffset == next.text.length,
        ) {
            "A prefix continuation must advance within the captured line"
        }
        return next
    }

    suspend fun repair(input: LineInput, request: RepairRequest): BracketGuide? {
        val facts = input.beginAttempt()
        control.checkCanceled()
        if (!request.pair.hasWellFormedTokenRange(facts.length)) return null
        val lastLine = facts.lineCount - 1
        val first = (
            if (request.pair.openLine < request.pair.closeLine) {
                request.pair.openLine + 1
            } else {
                request.pair.closeLine
            }
            ).coerceIn(0, lastLine)
        val lines = first..request.pair.closeLine.coerceIn(first, lastLine)
        val calculation = GuideRepairCalculation(
            request.pair,
            lines,
            facts.tabSize,
            request.exact,
            request.currentAnchorLine,
            checkCanceled,
        )
        while (true) {
            control.checkCanceled()
            val line = calculation.nextLine() ?: break
            val batch = input.initialPrefixes(line, 1)
            require(batch.firstLine == line && batch.prefixes.size == 1)
            var prefix = batch.prefixes.single()
            require(prefix.text.length <= 128 && prefix.lineEndOffset <= facts.length)
            while (!calculation.append(prefix.text, prefix.endOfLine)) {
                control.checkCanceled()
                prefix = continuation(input, line, prefix)
            }
        }
        val result = calculation.result()
        input.validateCurrent()
        control.checkCanceled()
        return result
    }

    private companion object {
        val ROLES: Array<BracketRole> = BracketRole.entries.toTypedArray()
        val STRUCTURAL: Array<StructuralRole> = StructuralRole.entries.toTypedArray()
    }
}
