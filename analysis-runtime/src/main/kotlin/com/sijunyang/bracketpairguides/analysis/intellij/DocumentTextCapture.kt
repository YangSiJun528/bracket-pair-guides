package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.util.text.CharArrayUtil

/** Sequential read-access scratch storage; only independently owned strings leave this adapter. */
internal class DocumentTextCapture(maximumCharacters: Int) {
    private val scratch = CharArray(maximumCharacters)

    fun copy(document: Document, start: Int, end: Int): String {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val length = end - start
        require(start >= 0 && length in 0..scratch.size && end <= document.textLength)
        // ImmutableText.subSequence().toString() allocates a fresh temporary char array per chunk.
        // Bulk-copy the current snapshot into our private scratch instead; String owns its copy.
        CharArrayUtil.getChars(document.immutableCharSequence, scratch, start, 0, length)
        return String(scratch, 0, length)
    }
}
