package com.sijunyang.bracketpairguides.renderer

import java.util.Arrays

/**
 * Sorts a primitive array without leaving cancellation unchecked for one large
 * monolithic [Arrays.sort] call.
 *
 * Small arrays keep the JDK's in-place fast path. Large arrays are sorted in
 * bounded chunks and then merged, checking cancellation both between chunks
 * and while copying long merge runs.
 */
internal fun LongArray.sortCancellable(checkCanceled: () -> Unit = {}) {
    checkCanceled()
    if (size < 2) return

    if (size <= CANCELLABLE_LONG_SORT_CHUNK_SIZE) {
        sort()
        checkCanceled()
        return
    }

    var chunkStart = 0
    while (chunkStart < size) {
        val chunkEnd = boundedEnd(chunkStart, CANCELLABLE_LONG_SORT_CHUNK_SIZE, size)
        Arrays.sort(this, chunkStart, chunkEnd)
        checkCanceled()
        chunkStart = chunkEnd
    }

    val buffer = LongArray(size)
    var source = this
    var destination = buffer
    var runLength = CANCELLABLE_LONG_SORT_CHUNK_SIZE
    while (runLength < size) {
        var runStart = 0
        while (runStart < size) {
            val middle = boundedEnd(runStart, runLength, size)
            val runEnd = boundedEnd(runStart, runLength.toLong() * 2, size)
            mergeSortedRuns(
                source = source,
                destination = destination,
                start = runStart,
                middle = middle,
                end = runEnd,
                checkCanceled = checkCanceled,
            )
            checkCanceled()
            runStart = runEnd
        }

        val previousSource = source
        source = destination
        destination = previousSource
        runLength = if (runLength > size / 2) size else runLength * 2
    }

    if (source !== this) {
        copyWithCancellation(source, this, checkCanceled)
    }
    checkCanceled()
}

private fun mergeSortedRuns(
    source: LongArray,
    destination: LongArray,
    start: Int,
    middle: Int,
    end: Int,
    checkCanceled: () -> Unit,
) {
    var left = start
    var right = middle
    var output = start
    while (left < middle && right < end) {
        destination[output++] = if (source[left] <= source[right]) {
            source[left++]
        } else {
            source[right++]
        }
        checkMergeProgress(output - start, checkCanceled)
    }
    while (left < middle) {
        destination[output++] = source[left++]
        checkMergeProgress(output - start, checkCanceled)
    }
    while (right < end) {
        destination[output++] = source[right++]
        checkMergeProgress(output - start, checkCanceled)
    }
}

private fun copyWithCancellation(
    source: LongArray,
    destination: LongArray,
    checkCanceled: () -> Unit,
) {
    var start = 0
    while (start < source.size) {
        val end = boundedEnd(start, CANCELLABLE_LONG_SORT_COPY_SIZE, source.size)
        source.copyInto(destination, destinationOffset = start, startIndex = start, endIndex = end)
        checkCanceled()
        start = end
    }
}

private fun checkMergeProgress(processed: Int, checkCanceled: () -> Unit) {
    if ((processed and CANCELLABLE_LONG_SORT_CHECK_MASK) == 0) checkCanceled()
}

private fun boundedEnd(start: Int, length: Int, size: Int): Int {
    return boundedEnd(start, length.toLong(), size)
}

private fun boundedEnd(start: Int, length: Long, size: Int): Int {
    return minOf(start.toLong() + length, size.toLong()).toInt()
}

internal const val CANCELLABLE_LONG_SORT_CHUNK_SIZE = 16_384
private const val CANCELLABLE_LONG_SORT_COPY_SIZE = 4_096
private const val CANCELLABLE_LONG_SORT_CHECK_MASK = CANCELLABLE_LONG_SORT_COPY_SIZE - 1
