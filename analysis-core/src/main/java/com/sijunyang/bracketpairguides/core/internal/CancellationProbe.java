package com.sijunyang.bracketpairguides.core.internal;

/** Cooperative interruption boundary supplied by the host runtime. */
@FunctionalInterface
interface CancellationProbe {
    void check();
}
