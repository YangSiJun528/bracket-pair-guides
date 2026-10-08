package com.sijunyang.bracketpairguides.core.internal;

/** Allocation-free output boundary for completed bracket pairs. */
@FunctionalInterface
interface PairSink {
    void accept(
            int openOffset,
            int openTokenLength,
            int closeOffset,
            int closeTokenLength,
            int depth,
            int openLine,
            int closeLine);
}
