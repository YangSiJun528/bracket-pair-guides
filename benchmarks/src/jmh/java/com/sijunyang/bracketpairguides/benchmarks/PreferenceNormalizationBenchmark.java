package com.sijunyang.bracketpairguides.benchmarks;

import com.sijunyang.bracketpairguides.settings.BracketGuidePreferenceNormalizationKt;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** Measures the caret-time identity path against full persisted-settings normalization. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@SuppressWarnings("KotlinInternalInJava") // Intentional benchmark-only production probe.
public class PreferenceNormalizationBenchmark {
    private com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences requested;
    private com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences current;

    @Setup
    public void setup() {
        current = new com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences();
        requested = current;
    }

    @Benchmark
    public com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
            reusePersistedSnapshot() {
        // Intentional slowdown for the Bencher merge-blocking test PR; do not merge.
        Blackhole.consumeCPU(100_000);
        return BracketGuidePreferenceNormalizationKt.normalizedForStorage(requested, current);
    }

    @Benchmark
    public com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
            normalizeDefaultSnapshot() {
        return BracketGuidePreferenceNormalizationKt.normalizedForStorage(requested, null);
    }
}
