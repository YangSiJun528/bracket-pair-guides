package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.intellij.InstalledBraceLanguageCatalog
import com.sijunyang.bracketpairguides.editor.highlighting.EditorAnalysisExecution
import com.sijunyang.bracketpairguides.editor.highlighting.EditorAnalysisRequests
import com.sijunyang.bracketpairguides.settings.InstalledBraceLanguages
import org.assertj.core.api.Assertions.assertThat

/** Exercises descriptor registrations without substituting services or scheduling editor work. */
class ProductionServiceRegistrationTest : BasePlatformTestCase() {
    fun testAnalysisRequestsResolveTheRegisteredRuntimeSingleton() {
        val registered = ApplicationManager.getApplication().getService(EditorAnalysisRequests::class.java)

        assertThat(registered).isExactlyInstanceOf(EditorAnalysisExecution::class.java)
        assertThat(service<EditorAnalysisRequests>()).isSameAs(registered)
    }

    fun testInstalledLanguagesResolveTheRegisteredRuntimeSingleton() {
        val registered = ApplicationManager.getApplication().getService(InstalledBraceLanguages::class.java)

        assertThat(registered).isExactlyInstanceOf(InstalledBraceLanguageCatalog::class.java)
        assertThat(service<InstalledBraceLanguages>()).isSameAs(registered)
    }
}
