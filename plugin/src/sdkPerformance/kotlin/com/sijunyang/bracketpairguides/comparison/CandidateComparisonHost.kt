package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.sijunyang.bracketpairguides.runtime.bootstrap.RuntimeGuideWorkFactory
import com.sijunyang.bracketpairguides.ui.measurement.UiSdkEditMeasurementSession
import com.sijunyang.bracketpairguides.ui.measurement.UiSdkGuideIsolation
import com.sijunyang.bracketpairguides.ui.measurement.UiSdkMeasurementSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** SDK performance composition only. Release and UI compiler inputs never include this source set. */
class CandidateComparisonHost private constructor(private val direct: CandidateRuntimeHost) : ComparisonHost by direct {
    constructor() : this(CandidateRuntimeHost())

    override fun assertNoUnownedAttachments() = UiSdkGuideIsolation.assertNoAttachments()

    override fun execution(
        editor: Editor,
        mode: String,
        reads: ReadRecorder,
        onPublication: () -> Unit,
    ): ExecutionHandle {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val root = SupervisorJob()
        val factory = RuntimeGuideWorkFactory(CoroutineScope(root + Dispatchers.Default + reads.context))
        var session: UiSdkMeasurementSession? = UiSdkMeasurementSession(editor, factory, mode)
        return object : ExecutionHandle {
            private var closed = false
            private var published = false
            override fun request() {
                published = false
                checkNotNull(session).request()
            }
            override fun markupCount(): Int {
                val count = session?.markupCount() ?: 0
                if (!published && count > 0) {
                    published = true
                    onPublication()
                }
                return count
            }
            override fun refresh() = checkNotNull(session).refresh()
            override fun markup(): List<Any> = session?.markup() ?: emptyList()
            override fun workerActive(): Boolean = if (closed) {
                !root.isCompleted
            } else {
                root.children.any { factoryOwner ->
                    factoryOwner.children.any { sessionOwner -> sessionOwner.children.any { it.isActive } }
                }
            }
            override fun close() {
                ApplicationManager.getApplication().assertIsDispatchThread()
                if (closed) return
                closed = true
                session?.close()
                session = null
                Disposer.dispose(factory)
                root.cancel()
            }
        }
    }

    override fun editSession(editor: Editor, reads: ReadRecorder): EditHandle {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val root = SupervisorJob()
        val factory = RuntimeGuideWorkFactory(CoroutineScope(root + Dispatchers.Default + reads.context))
        var session: UiSdkEditMeasurementSession? = try {
            UiSdkEditMeasurementSession(editor, factory)
        } catch (failure: Throwable) {
            try {
                Disposer.dispose(factory)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            } finally {
                root.cancel()
            }
            throw failure
        }
        return object : EditHandle {
            private var closed = false
            override fun markup(): List<Any> = session?.markup() ?: emptyList()
            override fun visibleGuide(): GuideShape? {
                val guide = session?.visibleGuide() ?: return null
                return GuideShape(
                    PairShape(
                        guide.openOffset,
                        guide.openTokenLength,
                        guide.openLine,
                        guide.closeOffset,
                        guide.closeTokenLength,
                        guide.closeLine,
                        guide.depth,
                    ),
                    guide.guideColumn,
                    guide.anchorLine,
                )
            }
            override fun workerActive(): Boolean = if (closed) {
                !root.isCompleted
            } else {
                root.children.any { factoryOwner ->
                    factoryOwner.children.any { sessionOwner -> sessionOwner.children.any { it.isActive } }
                }
            }
            override fun close() {
                ApplicationManager.getApplication().assertIsDispatchThread()
                if (closed) return
                closed = true
                val closing = session
                session = null
                try {
                    closing?.close()
                } finally {
                    try {
                        Disposer.dispose(factory)
                    } finally {
                        root.cancel()
                    }
                }
            }
        }
    }
}
