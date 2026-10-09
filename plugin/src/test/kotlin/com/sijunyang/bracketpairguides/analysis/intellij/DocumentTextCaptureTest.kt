package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class DocumentTextCaptureTest : BasePlatformTestCase() {
    fun testReturnedStringsSurviveScratchReuseAndDocumentEdits() {
        myFixture.configureByText("Capture.txt", " \t한글😃\nnext")
        val document = myFixture.editor.document
        val capture = DocumentTextCapture(8)
        val first = ReadAction.compute<String, RuntimeException> { capture.copy(document, 0, 6) }
        val second = ReadAction.compute<String, RuntimeException> { capture.copy(document, 7, 11) }
        WriteCommandAction.runWriteCommandAction(project) { document.setText("changed") }
        assertThat(first).isEqualTo(" \t한글😃")
        assertThat(second).isEqualTo("next")
    }
}
