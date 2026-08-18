package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.lang.BracePair
import com.intellij.psi.tree.IElementType

/** Distinguishes a true symmetric toggle from a token with mixed brace roles. */
internal class BracePairTopology(pairs: Array<BracePair>) {
    private val closesByOpen = HashMap<IElementType, MutableSet<IElementType>>(pairs.size)
    private val opensByClose = HashMap<IElementType, MutableSet<IElementType>>(pairs.size)
    private val structuralOpens = HashSet<IElementType>(pairs.size)
    private val structuralCloses = HashSet<IElementType>(pairs.size)

    init {
        for (pair in pairs) {
            closesByOpen.getOrPut(pair.leftBraceType) { HashSet() } += pair.rightBraceType
            opensByClose.getOrPut(pair.rightBraceType) { HashSet() } += pair.leftBraceType
            if (pair.isStructural) {
                structuralOpens += pair.leftBraceType
                structuralCloses += pair.rightBraceType
            }
        }
    }

    fun isStructuralOpen(type: IElementType): Boolean =
        type in structuralOpens

    fun isStructuralClose(type: IElementType): Boolean =
        type in structuralCloses

    fun isPureSymmetric(type: IElementType): Boolean {
        val outgoing = closesByOpen[type] ?: return false
        val incoming = opensByClose[type] ?: return false
        return outgoing.size == 1 && type in outgoing &&
            incoming.size == 1 && type in incoming
    }
}
