package com.sijunyang.bracketpairguides.benchmarks;

import com.sijunyang.bracketpairguides.analysis.index.CancellableLongArraySortKt;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Compares the production cancellable sort with the JDK primitive sort. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Thread)
public class LongArraySortBenchmark {
  private static final Function0<Unit> NO_CANCELLATION = () -> Unit.INSTANCE;

  /** Endpoint count. A snapshot normally creates two endpoints per bracket pair. */
  @Param({"32768", "200000", "1000000", "2000000"})
  public int size;

  @Param({"pair-events", "random", "ascending", "descending"})
  public String distribution;

  private long[] baseline;
  private long[] working;

  @Setup(Level.Trial)
  public void createBaseline() {
    baseline = BenchmarkLongArrays.create(size, distribution);
  }

  /** Copying is setup work and is excluded from the measured sort invocation. */
  @Setup(Level.Invocation)
  public void copyInput() {
    working = baseline.clone();
  }

  @Benchmark
  public long[] jdkSort() {
    Arrays.sort(working);
    return working;
  }

  @Benchmark
  public long[] productionCancellableSort() {
    CancellableLongArraySortKt.sortCancellable(working, NO_CANCELLATION);
    return working;
  }
}
