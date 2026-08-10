package com.sijunyang.bracketpairguides.analysis.pairing.core;

/** Allocation-free output boundary for completed bracket pairs. */
@FunctionalInterface
public interface PairSink {
    void accept(
            int openOffset,
            int openTokenLength,
            int closeOffset,
            int closeTokenLength,
            int depth,
            int openLine,
            int closeLine
    );
}
