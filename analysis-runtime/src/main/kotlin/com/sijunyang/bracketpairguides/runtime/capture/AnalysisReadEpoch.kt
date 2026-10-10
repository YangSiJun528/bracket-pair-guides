package com.sijunyang.bracketpairguides.runtime.capture

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeEvent
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import java.util.concurrent.atomic.AtomicLong

/** Conservative consistency ticket for capture chunks separated by released read locks. */
internal class AnalysisReadEpoch : Disposable {
    private val value = AtomicLong()
    private val environment = AtomicLong()
    val environmentRevision: Long
        get() = environment.get()
    val current: Long
        get() = value.get()

    init {
        val application = ApplicationManager.getApplication()
        application.addApplicationListener(
            object : ApplicationListener {
                override fun beforeWriteActionStart(action: Any) {
                    value.incrementAndGet()
                }
            },
            this,
        )
        val connection = application.messageBus.connect(this)
        connection.subscribe(
            FileTypeManager.TOPIC,
            object : FileTypeListener {
                override fun beforeFileTypesChanged(event: FileTypeEvent) {
                    environment.incrementAndGet()
                    value.incrementAndGet()
                }
            },
        )
        connection.subscribe(
            DynamicPluginListener.TOPIC,
            AnalysisPluginLifecycleListener {
                environment.incrementAndGet()
                value.incrementAndGet()
            },
        )
    }

    override fun dispose() = Unit
}
