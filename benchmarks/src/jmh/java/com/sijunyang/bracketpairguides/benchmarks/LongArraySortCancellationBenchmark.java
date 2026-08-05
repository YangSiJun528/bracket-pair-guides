package com.sijunyang.bracketpairguides.benchmarks;

import com.sijunyang.bracketpairguides.renderer.CancellableLongArraySortKt;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import kotlin.Unit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares how soon each implementation returns after cancellation is requested.
 * The JDK sort can observe cancellation only after its monolithic sort returns.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Thread)
public class LongArraySortCancellationBenchmark {
  @Param({"200000", "1000000", "2000000"})
  public int size;

  @Param({"1"})
  public long cancellationDelayMillis;

  private long[] baseline;
  private long[] working;
  private AtomicBoolean cancellationRequested;
  private ExecutorService cancellationExecutor;

  @Setup(Level.Trial)
  public void createState() {
    baseline = BenchmarkLongArrays.create(size, "pair-events");
    cancellationRequested = new AtomicBoolean();
    cancellationExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "sort-benchmark-canceller");
      thread.setDaemon(true);
      return thread;
    });
  }

  @Setup(Level.Invocation)
  public void copyInput() {
    working = baseline.clone();
    cancellationRequested.set(false);
  }

  @TearDown(Level.Trial)
  public void disposeState() {
    cancellationExecutor.shutdownNow();
  }

  @Benchmark
  public long jdkSortObservesCancellationAfterSort() throws Exception {
    Future<?> cancellation = requestCancellation();
    Arrays.sort(working);
    cancellation.get();
    if (!cancellationRequested.get()) {
      throw new AssertionError("Cancellation was not requested");
    }
    return working[0];
  }

  @Benchmark
  public long productionSortStopsCooperatively() throws Exception {
    Future<?> cancellation = requestCancellation();
    try {
      CancellableLongArraySortKt.sortCancellable(working, () -> {
        if (cancellationRequested.get()) {
          throw BenchmarkCancellation.INSTANCE;
        }
        return Unit.INSTANCE;
      });
    } catch (BenchmarkCancellation expected) {
      // Expected after the canceller publishes the request.
    }
    cancellation.get();
    return working[0];
  }

  private Future<?> requestCancellation() {
    CountDownLatch started = new CountDownLatch(1);
    Future<?> cancellation = cancellationExecutor.submit(() -> {
      started.countDown();
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(cancellationDelayMillis));
      cancellationRequested.set(true);
    });
    try {
      started.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted before benchmark sorting", exception);
    }
    return cancellation;
  }

  private static final class BenchmarkCancellation extends RuntimeException {
    private static final BenchmarkCancellation INSTANCE = new BenchmarkCancellation();

    private BenchmarkCancellation() {
      super(null, null, false, false);
    }
  }
}
