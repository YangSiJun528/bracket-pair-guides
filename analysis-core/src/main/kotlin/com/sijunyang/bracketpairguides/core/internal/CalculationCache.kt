package com.sijunyang.bracketpairguides.core.internal

import com.sijunyang.bracketpairguides.core.input.DocumentFacts
import java.lang.ref.WeakReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/** Weak canonical storage for a single document; late attempts cannot roll its generation backward. */
internal class CalculationCache {
    private val lock = ReentrantLock()
    private var revision = -1L
    private val entries = ArrayList<Entry>()

    fun admit(requestedRevision: Long, checkCanceled: () -> Unit) = locked(checkCanceled) {
        if (requestedRevision > revision) { revision = requestedRevision; entries.clear() }
    }

    fun canonical(facts: DocumentFacts, calculated: CalculatedAnalysis.Available,
        checkCanceled: () -> Unit): BracketIndexes = locked(checkCanceled) {
        if (facts.reuseRevision != revision) return@locked calculated.indexes
        val key = Key(calculated.layout, facts.tabSize.takeIf { calculated.indexes.guidePositions != null })
        val hash = calculated.canonicalPairs.contentHash()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            checkCanceled()
            val entry = iterator.next()
            val indexes = entry.indexes.get()
            val pairs = entry.pairs.get()
            if (indexes == null || entry.key.layout.activePair && pairs == null) { iterator.remove(); continue }
            if (entry.key != key) continue
            val equal = if (key.layout.activePair) entry.hash == hash &&
                checkNotNull(pairs).hasSameContent(calculated.canonicalPairs, CancellationProbe(checkCanceled))
                else indexes.tokens.hasSameContent(calculated.indexes.tokens, checkCanceled)
            if (equal) { checkCanceled(); return@locked indexes }
        }
        checkCanceled()
        entries.add(Entry(key, hash, WeakReference(calculated.canonicalPairs), WeakReference(calculated.indexes)))
        calculated.indexes
    }

    private fun <T> locked(checkCanceled: () -> Unit, body: () -> T): T {
        while (!lock.tryLock()) { checkCanceled(); LockSupport.parkNanos(250_000L) }
        return try { checkCanceled(); body() } finally { lock.unlock() }
    }
    private data class Key(val layout: IndexLayout, val tabSize: Int?)
    private class Entry(val key: Key, val hash: Int, val pairs: WeakReference<PairTable>, val indexes: WeakReference<BracketIndexes>)
}
