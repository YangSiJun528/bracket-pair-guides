package com.sijunyang.bracketpairguides.renderer

import com.intellij.openapi.editor.event.DocumentEvent

/** Immutable document-event data that remains safe if an EDT handoff is needed. */
internal data class DocumentChange(
    val offset: Int,
    val mayAffectBracketStructure: Boolean,
    val mayAffectGuidePosition: Boolean,
) {
    companion object {
        fun from(event: DocumentEvent): DocumentChange {
            return DocumentChange(
                offset = event.offset,
                mayAffectBracketStructure =
                    containsBracketContextCharacter(event.oldFragment) ||
                        containsBracketContextCharacter(event.newFragment),
                mayAffectGuidePosition =
                    containsGuidePositionCharacter(event.oldFragment) ||
                        containsGuidePositionCharacter(event.newFragment),
            )
        }

        private fun containsBracketContextCharacter(fragment: CharSequence): Boolean {
            if (fragment.length > MAX_CLASSIFIED_FRAGMENT_LENGTH) return true
            var offset = 0
            while (offset < fragment.length) {
                if (fragment[offset] in BRACKET_CONTEXT_CHARACTERS) return true
                offset++
            }
            return false
        }

        private fun containsGuidePositionCharacter(fragment: CharSequence): Boolean {
            if (fragment.length > MAX_CLASSIFIED_FRAGMENT_LENGTH) return true
            var offset = 0
            while (offset < fragment.length) {
                if (fragment[offset] == ' ' || fragment[offset] == '\t' ||
                    fragment[offset] == '\n' || fragment[offset] == '\r'
                ) {
                    return true
                }
                offset++
            }
            return false
        }

        private const val BRACKET_CONTEXT_CHARACTERS = "()[]{}<>\"'`/\\*"
        private const val MAX_CLASSIFIED_FRAGMENT_LENGTH = 4_096
    }
}
