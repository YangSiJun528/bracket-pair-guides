package com.sijunyang.bracketpairguides.model

data class OffsetRange(val startOffset: Int, val endOffset: Int) {
    init {
        require(startOffset in 0..endOffset) { "Offsets must be nonnegative and ordered" }
    }
}
