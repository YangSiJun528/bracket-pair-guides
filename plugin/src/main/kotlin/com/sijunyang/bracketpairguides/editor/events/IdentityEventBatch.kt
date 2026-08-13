package com.sijunyang.bracketpairguides.editor.events

import java.util.Collections
import java.util.IdentityHashMap

/** Coalesces equal-by-identity values into one fixed-delay callback. */
internal class IdentityEventBatch<T : Any>(
    private val schedule: (() -> Unit) -> Unit,
    private val consume: (T) -> Unit,
) {
    private val pending = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var scheduled = false

    fun request(value: T) {
        pending += value
        if (scheduled) return

        scheduled = true
        try {
            schedule(::flush)
        } catch (exception: RuntimeException) {
            scheduled = false
            throw exception
        }
    }

    fun remove(value: T) {
        pending -= value
    }

    fun clear() {
        pending.clear()
    }

    private fun flush() {
        val batch = pending.toList()
        pending.clear()
        scheduled = false
        for (value in batch) consume(value)
    }
}
