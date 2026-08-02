package com.sijunyang.bracketpairguides.analyzer

import com.intellij.lang.BracePair
import com.intellij.psi.tree.IElementType

/**
 * Preserves the full many-to-many relation allowed by [BracePair].
 *
 * A token receives toggle behavior only when its sole incoming and outgoing
 * edge is a self-pair. All mixed-role tokens keep the platform's forward
 * matching priority: an opening role wins over a closing role.
 */
internal class BraceTokenRules(pairs: Array<BracePair>) {
    private val closesByOpen = HashMap<IElementType, MutableSet<IElementType>>(pairs.size)
    private val opensByClose = HashMap<IElementType, MutableSet<IElementType>>(pairs.size)

    init {
        for (pair in pairs) {
            val left = pair.leftBraceType
            val right = pair.rightBraceType
            closesByOpen.getOrPut(left) { HashSet() } += right
            opensByClose.getOrPut(right) { HashSet() } += left
        }
    }

    fun expectedCloses(type: IElementType): Set<IElementType>? = closesByOpen[type]

    fun isClose(type: IElementType): Boolean = type in opensByClose

    fun isPureSymmetric(type: IElementType): Boolean {
        val outgoing = closesByOpen[type] ?: return false
        val incoming = opensByClose[type] ?: return false
        return outgoing.size == 1 && type in outgoing &&
            incoming.size == 1 && type in incoming
    }

    val isEmpty: Boolean
        get() = closesByOpen.isEmpty()

    companion object {
        val EMPTY = BraceTokenRules(emptyArray())
    }
}
