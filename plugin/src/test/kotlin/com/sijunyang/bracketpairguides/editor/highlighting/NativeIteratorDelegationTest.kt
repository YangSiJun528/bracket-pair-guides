package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class NativeIteratorDelegationTest : BasePlatformTestCase() {
    fun testBothIteratorLayersForwardStableOperations() = verifyStableForwarding()

    private fun verifyStableForwarding() {
        myFixture.configureByText("Forward.java", "class Forward {}")
        val original = myFixture.editor.highlighter.createIterator(0)
        val cancellable = CancellableNativeHighlighter.cancellableIterator(original) { }
        val tracked = NativeCursor.Tracked(cancellable)
        for (wrapper in listOf<HighlighterIterator>(cancellable, tracked)) {
            assertThat(wrapper.textAttributes).isEqualTo(original.textAttributes)
            assertThat(wrapper.start).isEqualTo(original.start)
            assertThat(wrapper.end).isEqualTo(original.end)
            assertThat(wrapper.tokenType).isSameAs(original.tokenType)
            assertThat(wrapper.atEnd()).isEqualTo(original.atEnd())
            assertThat(wrapper.document).isSameAs(original.document)
        }
    }
}
