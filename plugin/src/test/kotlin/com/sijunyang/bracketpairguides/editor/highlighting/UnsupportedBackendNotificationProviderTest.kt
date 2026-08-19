package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.HyperlinkLabel
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import java.awt.Component
import java.awt.Container
import org.assertj.core.api.Assertions.assertThat

class UnsupportedBackendNotificationProviderTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
        myFixture.configureByText("Backend.txt", "value")
        PropertiesComponent.getInstance(project).unsetValue(hiddenProperty())
    }

    override fun tearDown() {
        try {
            EditorGuideSessions.dispose(myFixture.editor)
            PropertiesComponent.getInstance(project).unsetValue(hiddenProperty())
        } finally {
            super.tearDown()
        }
    }

    fun testWarnsForUnavailableMatcherInEveryProduct() {
        publish(BraceMatcherAvailability.UNAVAILABLE)

        val generic = panel("IC")
        assertThat(generic).isNotNull
        assertThat(generic?.text).isEqualTo(UnsupportedBackendNotificationProvider.MESSAGE)

        for (productCode in listOf("RD", "CL")) {
            val reSharper = panel(productCode)

            assertThat(reSharper).isNotNull
            assertThat(reSharper?.text)
                .isEqualTo(UnsupportedBackendNotificationProvider.RESHARPER_MESSAGE)
        }
    }

    fun testDoesNotWarnForNonUnavailableStates() {
        for (availability in listOf(
            BraceMatcherAvailability.AVAILABLE,
            BraceMatcherAvailability.DISABLED,
            BraceMatcherAvailability.UNDETERMINED,
        )) {
            publish(availability)

            assertThat(panel("IC"))
                .describedAs("generic panel for %s", availability)
                .isNull()
            assertThat(panel("RD"))
                .describedAs("Rider panel for %s", availability)
                .isNull()
        }
    }

    fun testGenericActionOpensLanguageSupportReference() {
        publish(BraceMatcherAvailability.UNAVAILABLE)
        var openedUrl: String? = null
        val panel = panel("IC") { url -> openedUrl = url }

        panel?.action(UnsupportedBackendNotificationProvider.DOCUMENTATION_ACTION_TEXT)
            ?.doClick()

        assertThat(openedUrl)
            .isEqualTo(UnsupportedBackendNotificationProvider.LANGUAGE_SUPPORT_URL)
    }

    fun testReSharperActionOpensSupportRequest() {
        publish(BraceMatcherAvailability.UNAVAILABLE)
        var openedUrl: String? = null
        val panel = panel("RD") { url -> openedUrl = url }

        panel?.action(UnsupportedBackendNotificationProvider.SUPPORT_ACTION_TEXT)
            ?.doClick()

        assertThat(openedUrl)
            .isEqualTo("https://github.com/YangSiJun528/bracket-pair-guides/issues/19")
    }

    fun testFileTypeSuppressionHidesTheWarning() {
        publish(BraceMatcherAvailability.UNAVAILABLE)
        PropertiesComponent.getInstance(project).setValue(hiddenProperty(), true)

        assertThat(panel("IC")).isNull()
        assertThat(panel("CL")).isNull()
    }

    private fun publish(availability: BraceMatcherAvailability) {
        val options = BracketGuideSettings.getInstance().options
        val input = AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = options.analysisCoverage(),
            disabledLanguageIds = options.disabledLanguageIds,
        )
        val session = EditorGuideSessions.install(
            editor = myFixture.editor,
            visibleRange = { editor -> TextRange(0, editor.document.textLength) },
            preferences = options,
        )
        session.accept(
            AnalysisOutcome.Complete(
                input.bracketSnapshot(emptyList(), availability),
            ),
        )
    }

    private fun panel(
        productCode: String,
        openUrl: (String) -> Unit = {},
    ) = UnsupportedBackendNotificationProvider(productCode, openUrl).notificationPanel(
        project,
        myFixture.file.virtualFile,
        myFixture.editor,
    )

    private fun hiddenProperty(): String =
        UnsupportedBackendNotificationProvider.hiddenProperty(myFixture.file.virtualFile)

    private fun Component.action(text: String): HyperlinkLabel? = descendants()
        .filterIsInstance<HyperlinkLabel>()
        .singleOrNull { label -> label.text == text }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { component ->
                addAll(component.descendants())
            }
        }
    }
}
