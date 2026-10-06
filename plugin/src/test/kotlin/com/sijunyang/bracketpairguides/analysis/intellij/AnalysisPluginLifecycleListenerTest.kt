package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicLong

class AnalysisPluginLifecycleListenerTest : BasePlatformTestCase() {
    fun testLoadAndBothUnloadModesInvalidateBeforeTheChange() {
        val descriptor = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java")))
        val epoch = AtomicLong()
        val listener = AnalysisPluginLifecycleListener { epoch.incrementAndGet() }

        listener.beforePluginLoaded(descriptor)
        assertThat(epoch.get()).isEqualTo(1L)
        listener.pluginLoaded(descriptor)
        assertThat(epoch.get()).isEqualTo(1L)

        listener.beforePluginUnload(descriptor, false)
        assertThat(epoch.get()).isEqualTo(2L)
        listener.pluginUnloaded(descriptor, false)
        assertThat(epoch.get()).isEqualTo(2L)

        listener.beforePluginUnload(descriptor, true)
        assertThat(epoch.get()).isEqualTo(3L)
        listener.pluginUnloaded(descriptor, true)
        assertThat(epoch.get()).isEqualTo(3L)
    }
}
