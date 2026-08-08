package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.event.DocumentEvent
import org.jetbrains.annotations.ApiStatus

/** Immutable document-event data that remains safe if an EDT handoff is needed. */
@ApiStatus.Internal
internal data class DocumentChange(
    public val offset: Int,
    public val mayAffectGuidePosition: Boolean,
) {
    public companion object {
        public fun from(event: DocumentEvent): DocumentChange {
            return DocumentChange(
                offset = event.offset,
                mayAffectGuidePosition =
                    containsGuidePositionCharacter(event.oldFragment) ||
                        containsGuidePositionCharacter(event.newFragment),
            )
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

        private const val MAX_CLASSIFIED_FRAGMENT_LENGTH = 4_096
    }
}
