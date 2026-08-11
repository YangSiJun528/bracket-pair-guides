package com.sijunyang.bracketpairguides.analysis.pairing.core;

/** Cooperative interruption boundary supplied by the host runtime. */
@FunctionalInterface
public interface CancellationProbe {
    void check();
}
