package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/**
 * Weak, revision-scoped canonical forms for editor-independent bracket indexes.
 * Neither a generation nor an entry strongly retains its Document, Editor,
 * FileType, highlighter, pair table, indexes, or stamp.
 */
internal class DocumentBracketIndexes {
    private val generations = WeakHashMap<Document, DocumentGeneration>()

    internal fun canonical(
        input: AnalysisInput,
        layout: IndexLayout,
        pairs: PairTable,
        candidate: BracketIndexes,
        checkCanceled: () -> Unit,
    ): BracketIndexes {
        checkCanceled()
        val document = input.editor.document
        val revision = document.modificationStamp
        val generation = synchronized(generations) {
            generations[document]
                ?.takeIf { current -> current.revision == revision }
                ?: DocumentGeneration(revision).also { current ->
                    generations[document] = current
                }
        }
        val identity = IndexIdentity(
            layout = layout,
            coverage = input.coverage,
            guideTabSize = input.stamp.tabSize.takeIf {
                candidate.guidePositions != null
            },
            fileType = input.fileType,
            disabledLanguageIds = input.disabledLanguageIds,
        )
        return generation.canonical(
            identity = identity,
            pairs = pairs,
            candidate = candidate,
            checkCanceled = checkCanceled,
        )
    }
}

private class DocumentGeneration(
    val revision: Long,
) {
    private val lock = ReentrantLock()
    private val entries: MutableList<BracketIndexReference> = ArrayList()

    fun canonical(
        identity: IndexIdentity,
        pairs: PairTable,
        candidate: BracketIndexes,
        checkCanceled: () -> Unit,
    ): BracketIndexes {
        acquire(checkCanceled)
        try {
            checkCanceled()
            val candidateHash = pairs.contentHash()
            val cancellation = CancellationProbe(checkCanceled)
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                checkCanceled()
                val entry = iterator.next()
                val existingIndexes = entry.indexes.get()
                val existingPairs = entry.pairs.get()
                if (existingIndexes == null ||
                    entry.identity.requiresPairGeometry && existingPairs == null
                ) {
                    iterator.remove()
                    continue
                }
                if (!entry.identity.sameAs(identity)) continue
                val hasSameContent = if (identity.requiresPairGeometry) {
                    entry.pairHash == candidateHash &&
                        checkNotNull(existingPairs).hasSameContent(
                            pairs,
                            cancellation,
                        )
                } else {
                    existingIndexes.tokens.hasSameContent(
                        candidate.tokens,
                        checkCanceled,
                    )
                }
                if (hasSameContent) return existingIndexes
            }
            this.entries += BracketIndexReference(
                identity = identity,
                pairHash = candidateHash,
                pairs = WeakReference(pairs),
                indexes = WeakReference(candidate),
            )
            return candidate
        } finally {
            lock.unlock()
        }
    }

    private fun acquire(checkCanceled: () -> Unit) {
        while (!lock.tryLock()) {
            checkCanceled()
            LockSupport.parkNanos(LOCK_RETRY_NANOS)
        }
    }

    private companion object {
        const val LOCK_RETRY_NANOS: Long = 250_000L
    }
}

private class IndexIdentity(
    private val layout: IndexLayout,
    private val coverage: AnalysisCoverage,
    private val guideTabSize: Int?,
    fileType: FileType,
    private val disabledLanguageIds: Set<String>,
) {
    private val fileType = WeakReference(fileType)
    val requiresPairGeometry: Boolean
        get() = layout.activePair

    fun sameAs(other: IndexIdentity): Boolean {
        val currentFileType = fileType.get() ?: return false
        val otherFileType = other.fileType.get() ?: return false
        return layout == other.layout &&
            coverage == other.coverage &&
            guideTabSize == other.guideTabSize &&
            currentFileType === otherFileType &&
            disabledLanguageIds == other.disabledLanguageIds
    }
}

private class BracketIndexReference(
    val identity: IndexIdentity,
    val pairHash: Int,
    val pairs: WeakReference<PairTable>,
    val indexes: WeakReference<BracketIndexes>,
)
