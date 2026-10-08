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
                    value.incrementAndGet()
                }
            },
        )
        connection.subscribe(
            DynamicPluginListener.TOPIC,
            AnalysisPluginLifecycleListener { value.incrementAndGet() },
        )
    }

    override fun dispose() = Unit
}
