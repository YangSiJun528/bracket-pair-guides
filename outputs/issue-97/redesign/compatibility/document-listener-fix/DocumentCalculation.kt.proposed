package com.sijunyang.bracketpairguides.runtime.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer
import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import java.lang.ref.WeakReference

/** A document-scoped calculator and a monotone revision independent of host stamp semantics. */
internal class DocumentCalculation(document: Document) : DocumentListener {
    val calculator = BracketCalculator()
    private val documentReference = WeakReference(document)
    private var observedStamp = document.modificationStamp
    private var revision = 0L
    private var owners = 0
    private var listenerLifetime: Disposable? = null

    @Synchronized fun acquire() {
        val current = checkNotNull(documentReference.get())
        if (owners == 0) {
            val lifetime = Disposer.newDisposable("Bracket Pair Guides document calculation")
            try {
                current.addDocumentListener(this, lifetime)
            } catch (failure: Throwable) {
                Disposer.dispose(lifetime)
                throw failure
            }
            listenerLifetime = lifetime
            revision++ // Dormant intervals have no listener; never reuse their cached geometry blindly.
            revisionFor(current.modificationStamp)
        }
        owners++
    }

    @Synchronized fun release() {
        check(owners > 0)
        if (--owners == 0) {
            val lifetime = checkNotNull(listenerLifetime)
            listenerLifetime = null
            Disposer.dispose(lifetime)
        }
    }

    @Synchronized override fun documentChanged(event: DocumentEvent) {
        observedStamp = event.document.modificationStamp
        revision++
    }

    /** Called only after validating the captured source inside real read access. */
    @Synchronized fun revisionFor(stamp: Long): Long {
        if (observedStamp != stamp) {
            observedStamp = stamp
            revision++
        }
        return revision
    }
}
