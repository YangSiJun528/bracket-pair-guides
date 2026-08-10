package com.sijunyang.bracketpairguides.editor

import java.util.Collections
import java.util.IdentityHashMap

/** Coalesces equal-by-identity values into one fixed-delay callback. */
internal class IdentityEventBatch<T : Any>(
    private val schedule: (() -> Unit) -> Unit,
    private val consume: (T) -> Unit,
) {
    private val pending = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var scheduled = false

    fun request(value: T): Unit {
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

    fun remove(value: T): Unit {
        pending -= value
    }

    fun clear(): Unit {
        pending.clear()
    }

    private fun flush() {
        val batch = pending.toList()
        pending.clear()
        scheduled = false
        for (value in batch) consume(value)
    }
}
