package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.application.EDT
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fresh shared operations. The old timing harness is not used by these comparisons. */
suspend fun ComparisonFixture.runRepairWorkload() {
    for (corpus in repairCorpora()) {
        val editor = editor("RepairMeasure.java", corpus.text)
        try {
            withContext(Dispatchers.EDT) { editor.settings.setTabSize(4) }
            val result = host.analyze(editor, "all")
            val pair = checkNotNull(result.sample(corpus.editOffset))
            check(pair.open == 0 && pair.close == corpus.text.lastIndex)
            emit("kind" to "corpus", "workload" to "repair", "corpus" to corpus.name,
                "sha256Utf8" to fingerprint(corpus.text), "filename" to "RepairMeasure.java",
                "characters" to corpus.text.length,
                "tabSize" to 4, "candidateLines" to corpus.candidateLines,
                "consumedPrefixCharacters" to corpus.consumedPrefixCharacters,
                "lineLimit" to 256, "characterLimit" to 32768,
                "expectedRefusal" to corpus.refuses, "expectedGuideColumn" to corpus.column,
                "scope" to "actual direct worker repair including source capture; UI hide/enqueue/publication excluded",
                "sameLineScope" to if (corpus.candidateLines == 0)
                    "direct worker API; original UI special case does not schedule a worker" else null,
                "readBodyCoverage" to "baseline repair has no read observer; worker wall is not read hold time")
            repeat(warmups + repeats) { index ->
                // Same one-character whitespace edit as the original boundary corpora; offsets stay fixed.
                edit(editor, corpus.editOffset, 1, if (index % 2 == 0) "\t" else " ")
                val repaired = if (index < warmups) {
                    host.repair(editor, result, corpus.editOffset, true)
                } else {
                    measure("repair", corpus.name, index - warmups,
                        mapOf("exact" to true, "tabbed" to (index % 2 == 0),
                            "expectedRefusal" to corpus.refuses)) {
                        host.repair(editor, result, corpus.editOffset, true)
                    }
                }
                check((repaired == null) == corpus.refuses) {
                    "Repair budget behavior changed for ${corpus.name}: $repaired"
                }
                if (repaired != null) {
                    check(repaired.pair == pair && repaired.guideColumn == corpus.column) {
                        "Repair geometry changed for ${corpus.name}: $repaired"
                    }
                }
                if (index >= warmups) emit("kind" to "result", "workload" to "repair",
                    "corpus" to corpus.name, "iteration" to index - warmups,
                    "refused" to (repaired == null), "guideColumn" to repaired?.guideColumn,
                    "pair" to repaired?.pair)
            }
        } finally {
            release(editor)
        }
    }
}

suspend fun ComparisonFixture.runNativeWorkload() {
    val java = "class NativeMeasure {\n  void run() {\n" + "    call(1);\n".repeat(20000) + "  }\n}\n"
    val methodOpen = java.indexOf('{', java.indexOf("void"))
    val xml = "<root>\n" + "  <item><value>x</value></item>\n".repeat(5000) + "</root>\n"
    val javaCarets = listOf("direct" to methodOpen, "scope" to java.lastIndexOf("call") + 1)
    val corpora = listOf(
        NativeCorpus("large-java", "NativeMeasure.java", java, methodOpen, javaCarets),
        NativeCorpus("large-java-lazy", "NativeLazyMeasure.java", java, methodOpen, javaCarets, true),
        NativeCorpus("large-xml", "NativeMeasure.xml", xml, 0, listOf("direct" to 0)),
    )
    for (corpus in corpora) {
        val editor = editor(corpus.filename, corpus.text)
        try {
            withContext(Dispatchers.EDT) { editor.settings.isBlockCursor = false }
            if (corpus.forceLazy) forceNativeLazyLanguage(editor, corpus.pairOffset)
            val result = host.analyze(editor, "all")
            val pair = checkNotNull(result.sample(corpus.pairOffset))
            check(pair.open == corpus.pairOffset)
            emit("kind" to "corpus", "workload" to "native", "corpus" to corpus.name,
                "sha256Utf8" to fingerprint(corpus.text), "filename" to corpus.filename,
                "characters" to corpus.text.length, "pairOffset" to corpus.pairOffset, "pair" to pair,
                "forcedLazyLanguageBranch" to corpus.forceLazy,
                "scope" to "actual SDK native source resolver; analysis, setup, UI eligibility and painting excluded")
            for ((mode, caret) in corpus.carets) {
                repeat(warmups) { host.native(editor, result, corpus.pairOffset, caret, true) }
                val writers = if (corpus.forceLazy) listOf("none", "late-traversal", "late-lazy-lexer")
                    else listOf("none", "late-traversal")
                for (writer in writers) {
                  var witnessedWriterInsideRead = 0
                  repeat(repeats) { index ->
                    val reads = host.newReads()
                    val probe = NativeWriterProbe(reads, mode, writer)
                    var resolved: NativeShape? = null
                    var resolutionFailure: Throwable? = null
                    try {
                        probe.installLazyLexer(editor)
                        try {
                            resolved = measure("native", "${corpus.name}:$mode", index,
                                mapOf("mode" to mode, "writerMode" to writer, "pairOffset" to corpus.pairOffset,
                                    "caretOffset" to caret, "resolveCurrentScope" to true), reads) {
                                try { host.native(editor, result, corpus.pairOffset, caret, true) }
                                finally { probe.resolutionEnded() }
                            }
                        } catch (failure: Throwable) {
                            resolutionFailure = failure
                        }
                        val writerEvidence = probe.finish()
                        if (writerEvidence["writeRequestedInsideTriggeredPhase"] == true) witnessedWriterInsideRead++
                        if (resolved != null) {
                            check(resolved.available && resolved.directMarkers in 0..1 && resolved.scopeMarkers in 0..1)
                        }
                        emit("kind" to "result", "workload" to "native", "corpus" to "${corpus.name}:$mode",
                            "iteration" to index, "writerMode" to writer, "writer" to writerEvidence,
                            "actualResolverCalled" to resolved?.available,
                            "directMarkers" to resolved?.directMarkers, "scopeMarkers" to resolved?.scopeMarkers,
                            "resolutionFailureClass" to resolutionFailure?.javaClass?.name,
                            "nativePaintVerified" to false)
                        resolutionFailure?.let { throw it }
                        if (writer == "none") {
                            check(resolutionFailure == null && resolved?.available == true)
                            val completed = checkNotNull(resolved)
                            if (corpus.forceLazy) check(reads.snapshot().any { it.phase == "native-preparation" }) {
                                "Forced lazy setup did not exercise an actual preparation read body"
                            }
                            check(if (mode == "scope") completed.scopeMarkers == 1 else completed.directMarkers == 1) {
                                "Expected actual native marker proof for ${corpus.name}:$mode"
                            }
                        }
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            try { probe.finish() }
                            finally { withContext(Dispatchers.EDT) { probe.close() } }
                        }
                    }
                  }
                  emit("kind" to "native-control-coverage", "corpus" to "${corpus.name}:$mode",
                      "writerMode" to writer, "attemptedSamples" to repeats,
                      "samplesWithActualWriterInsideTriggeredPhase" to witnessedWriterInsideRead)
                  if (writer != "none") check(witnessedWriterInsideRead > 0) {
                      "No actual triggered-phase read/write overlap observed for ${corpus.name}:$mode:$writer; raw misses retained"
                  }
                }
            }
        } finally { release(editor) }
    }
}

private data class RepairCorpus(val name: String, val text: String, val editOffset: Int,
    val candidateLines: Int, val consumedPrefixCharacters: Int, val refuses: Boolean, val column: Int)

private fun repairCorpora(): List<RepairCorpus> {
    fun body(name: String, lines: Int, indent: Int, refuses: Boolean = false): RepairCorpus = RepairCorpus(
        name, "{\n" + (" ".repeat(indent) + "value\n").repeat(lines) + " ".repeat(indent) + "}",
        2, lines + 1, (lines + 1) * (indent + 1), refuses, indent)
    fun longIndent(name: String, indent: Int, refuses: Boolean) = RepairCorpus(
        name, "{\n" + " ".repeat(indent) + "value\n        }", 2, 2, indent + 10, refuses, 8)
    return listOf(
        body("ordinary-small-body", 7, 4),
        body("exact-256-line-budget", 255, 8),
        body("257-line-refusal", 256, 8, true),
        longIndent("exact-32768-character-budget", 32758, false),
        longIndent("32769-character-refusal", 32759, true),
        RepairCorpus("same-line-special-case", "{ value }", 1, 0, 0, false, 0),
    )
}

private data class NativeCorpus(val name: String, val filename: String, val text: String,
    val pairOffset: Int, val carets: List<Pair<String, Int>>, val forceLazy: Boolean = false)

private fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
