package com.sijunyang.bracketpairguides.runtime.bootstrap

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisReadEpoch
import com.sijunyang.bracketpairguides.runtime.session.DocumentCalculation
import com.sijunyang.bracketpairguides.runtime.session.EditorAnalysisSession
import com.sijunyang.bracketpairguides.ui.work.GuideView
import com.sijunyang.bracketpairguides.ui.work.GuideWork
import com.sijunyang.bracketpairguides.ui.work.GuideWorkFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Composition exports UI contracts only; calculators and host identity stay inside runtime. */
class RuntimeGuideWorkFactory(parent: CoroutineScope) :
    GuideWorkFactory,
    Disposable {
    private val root = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + root)
    private val epoch = AnalysisReadEpoch()
    private val calculators = WeakHashMap<Document, WeakReference<DocumentCalculation>>()

    init {
        Disposer.register(this, epoch)
    }

    override fun attach(editor: Editor, view: GuideView): GuideWork {
        check(root.isActive) { "Runtime factory is disposed" }
        val calculation = synchronized(calculators) {
            calculators[editor.document]?.get() ?: DocumentCalculation(editor.document).also {
                calculators[editor.document] = WeakReference(it)
            }
        }
        return EditorAnalysisSession(editor, view, scope, epoch, calculation)
    }

    override fun dispose() {
        root.cancel()
        synchronized(calculators) { calculators.clear() }
    }
}
