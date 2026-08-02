package com.sijunyang.bracketpairguides.renderer

import com.intellij.openapi.editor.event.DocumentEvent

/** Immutable document-event data that remains safe if an EDT handoff is needed. */
internal data class DocumentChange(
    val offset: Int,
    val oldLineBreakCount: Int,
    val newLineBreakCount: Int,
    val mayAffectBracketStructure: Boolean,
) {
    companion object {
        fun from(event: DocumentEvent): DocumentChange {
            return DocumentChange(
                offset = event.offset,
                oldLineBreakCount = lineBreakCount(event.oldFragment),
                newLineBreakCount = lineBreakCount(event.newFragment),
                mayAffectBracketStructure =
                    containsBracketContextCharacter(event.oldFragment) ||
                        containsBracketContextCharacter(event.newFragment),
            )
        }

        private fun lineBreakCount(fragment: CharSequence): Int {
            var count = 0
            var offset = 0
            while (offset < fragment.length) {
                if (fragment[offset] == '\n') count++
                offset++
            }
            return count
        }

        private fun containsBracketContextCharacter(fragment: CharSequence): Boolean {
            var offset = 0
            while (offset < fragment.length) {
                if (fragment[offset] in BRACKET_CONTEXT_CHARACTERS) return true
                offset++
            }
            return false
        }

        private const val BRACKET_CONTEXT_CHARACTERS = "()[]{}<>\"'`/\\*"
    }
}
