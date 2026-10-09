package com.sijunyang.bracketpairguides.benchmarks;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CalculationContractBenchmark {
    @Param({"64", "4096"})
    public int pairCount;

    @Param({"siblings", "nested", "sparse", "malformed"})
    public String distribution;

    private CalculationWorkload workload;

    @Setup
    public void prepare() {
        workload = new CalculationWorkload(pairCount, distribution);
    }

    @Benchmark
    public Object analyzeCold() {
        return workload.analyzeCold();
    }

    @Benchmark
    public Object analyzeReuse() {
        return workload.analyzeReuse();
    }

    @Benchmark
    public int visibleQuery() {
        return workload.query();
    }

    @Benchmark
    public Object repair() {
        return workload.repair();
    }

    @Benchmark
    public boolean cancelledAttempt() {
        return workload.cancel();
    }
}
