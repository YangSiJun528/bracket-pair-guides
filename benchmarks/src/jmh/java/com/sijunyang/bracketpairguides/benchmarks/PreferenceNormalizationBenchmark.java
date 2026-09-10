package com.sijunyang.bracketpairguides.benchmarks;

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences;
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferenceNormalizationKt;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Measures the caret-time identity path against full persisted-settings normalization. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class PreferenceNormalizationBenchmark {
    private BracketGuidePreferences requested;
    private BracketGuidePreferences current;

    @Setup
    public void setup() {
        current = new BracketGuidePreferences();
        requested = current;
    }

    @Benchmark
    @SuppressWarnings("KotlinInternalInJava") // Intentional benchmark-only production probe.
    public BracketGuidePreferences reusePersistedSnapshot() {
        return BracketGuidePreferenceNormalizationKt.normalizedForStorage(requested, current);
    }

    @Benchmark
    @SuppressWarnings("KotlinInternalInJava") // Intentional benchmark-only production probe.
    public BracketGuidePreferences normalizeDefaultSnapshot() {
        return BracketGuidePreferenceNormalizationKt.normalizedForStorage(requested, null);
    }
}
