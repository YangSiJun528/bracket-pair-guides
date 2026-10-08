package com.sijunyang.bracketpairguides.model

data class OffsetRange(val startOffset: Int, val endOffset: Int) {
    init {
        require(startOffset >= 0 && endOffset >= startOffset) { "Offsets must be nonnegative and ordered" }
    }
}
