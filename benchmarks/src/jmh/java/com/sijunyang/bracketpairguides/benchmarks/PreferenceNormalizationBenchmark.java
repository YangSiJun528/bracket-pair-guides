package com.sijunyang.bracketpairguides.benchmarks;

//noinspection KotlinInternalInJava -- Intentional benchmark-only production probe.
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
@SuppressWarnings("KotlinInternalInJava") // Intentional benchmark-only production probe.
public class PreferenceNormalizationBenchmark {
    private BracketGuidePreferences requested;
    private BracketGuidePreferences current;

    @Setup
    public void setup() {
        current = new BracketGuidePreferences();
        requested = current;
    }

    @Benchmark
    public BracketGuidePreferences reusePersistedSnapshot() {
        return BracketGuidePreferenceNormalizationKt.normalizedForStorage(requested, current);
    }

    @Benchmark
    public BracketGuidePreferences normalizeDefaultSnapshot() {
        return BracketGuidePreferenceNormalizationKt.normalizedForStorage(requested, null);
    }
}
