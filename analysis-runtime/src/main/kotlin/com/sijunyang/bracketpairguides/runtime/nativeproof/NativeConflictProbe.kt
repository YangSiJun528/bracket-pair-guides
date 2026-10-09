package com.sijunyang.bracketpairguides.runtime.nativeproof

/** EDT-captured inspection. Expensive platform inspection runs only on a background worker. */
internal fun interface NativeConflictProbe {
    suspend fun inspect(checkCanceled: () -> Unit): Boolean

    /** Revalidate cheap visual facts on EDT immediately before accepting a completed inspection. */
    fun isCurrent(): Boolean = true
}
