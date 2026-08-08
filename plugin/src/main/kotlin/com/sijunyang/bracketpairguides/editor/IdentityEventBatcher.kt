package com.sijunyang.bracketpairguides.editor

import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.IdentityHashMap

/** Coalesces equal-by-identity values into one fixed-delay callback. */
@ApiStatus.Internal
internal class IdentityEventBatcher<T : Any>(
    private val schedule: (() -> Unit) -> Unit,
    private val consume: (T) -> Unit,
) {
    private val pending = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var scheduled = false

    public fun request(value: T): Unit {
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

    public fun remove(value: T): Unit {
        pending -= value
    }

    public fun clear(): Unit {
        pending.clear()
    }

    private fun flush() {
        val batch = pending.toList()
        pending.clear()
        scheduled = false
        for (value in batch) consume(value)
    }
}
