package com.sijunyang.bracketpairguides.analyzer

/**
 * Sorts analyzer output without one unbounded object-array sort.
 *
 * Each platform sort is limited to a fixed-size view. The sorted runs are then
 * merged stably, checking cancellation while reading and copying long runs.
 * Callers discard this private result list when cancellation is raised, so a
 * partially sorted list is never published in an analysis snapshot.
 */
internal fun MutableList<BracketPair>.sortBracketPairsCancellable(
    checkCanceled: () -> Unit = {},
) {
    checkCanceled()
    if (size < 2) return

    if (size <= CANCELLABLE_PAIR_SORT_CHUNK_SIZE) {
        sortWith(BRACKET_PAIR_ORDER)
        checkCanceled()
        return
    }

    var chunkStart = 0
    while (chunkStart < size) {
        val chunkEnd = boundedEnd(chunkStart, CANCELLABLE_PAIR_SORT_CHUNK_SIZE, size)
        subList(chunkStart, chunkEnd).sortWith(BRACKET_PAIR_ORDER)
        checkCanceled()
        chunkStart = chunkEnd
    }

    val buffer = arrayOfNulls<BracketPair>(size)
    var runLength = CANCELLABLE_PAIR_SORT_CHUNK_SIZE
    while (runLength < size) {
        var runStart = 0
        while (runStart < size) {
            val middle = boundedEnd(runStart, runLength, size)
            val runEnd = boundedEnd(runStart, runLength.toLong() * 2, size)
            mergeSortedRuns(
                source = this,
                destination = buffer,
                start = runStart,
                middle = middle,
                end = runEnd,
                checkCanceled = checkCanceled,
            )
            checkCanceled()
            runStart = runEnd
        }

        copyBack(buffer, this, checkCanceled)
        runLength = if (runLength > size / 2) size else runLength * 2
    }
    checkCanceled()
}

private fun mergeSortedRuns(
    source: List<BracketPair>,
    destination: Array<BracketPair?>,
    start: Int,
    middle: Int,
    end: Int,
    checkCanceled: () -> Unit,
) {
    var left = start
    var right = middle
    var output = start
    while (left < middle && right < end) {
        val leftPair = source[left]
        val rightPair = source[right]
        destination[output++] = if (BRACKET_PAIR_ORDER.compare(leftPair, rightPair) <= 0) {
            left++
            leftPair
        } else {
            right++
            rightPair
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

private fun copyBack(
    source: Array<BracketPair?>,
    destination: MutableList<BracketPair>,
    checkCanceled: () -> Unit,
) {
    var index = 0
    while (index < source.size) {
        destination[index] = checkNotNull(source[index])
        index++
        if (index and CANCELLABLE_PAIR_SORT_CHECK_MASK == 0) checkCanceled()
    }
    checkCanceled()
}

private fun checkMergeProgress(processed: Int, checkCanceled: () -> Unit) {
    if (processed and CANCELLABLE_PAIR_SORT_CHECK_MASK == 0) checkCanceled()
}

private fun boundedEnd(start: Int, length: Int, size: Int): Int =
    boundedEnd(start, length.toLong(), size)

private fun boundedEnd(start: Int, length: Long, size: Int): Int =
    minOf(start.toLong() + length, size.toLong()).toInt()

private val BRACKET_PAIR_ORDER = Comparator<BracketPair> { first, second ->
    val openOrder = first.openOffset.compareTo(second.openOffset)
    if (openOrder != 0) openOrder else first.closeOffset.compareTo(second.closeOffset)
}

internal const val CANCELLABLE_PAIR_SORT_CHUNK_SIZE = 16_384
private const val CANCELLABLE_PAIR_SORT_CHECK_MASK = 4_096 - 1
