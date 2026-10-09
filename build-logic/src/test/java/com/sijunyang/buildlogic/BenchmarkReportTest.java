package com.sijunyang.buildlogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BenchmarkReportTest {
    List<Map<String, Object>> report() {
        var rows = new ArrayList<Map<String, Object>>();
        for (String size : List.of("64", "4096")) {
            for (String distribution : List.of("siblings", "nested", "sparse", "malformed")) {
                var row = new LinkedHashMap<String, Object>();
                row.put(
                        "benchmark",
                        "com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark.analyzeCold");
                row.put("params", Map.of("pairCount", size, "distribution", distribution));
                row.put("mode", "avgt");
                row.put("jmhVersion", "1.37");
                row.put("jdkVersion", "17.0.20");
                row.put("forks", 2);
                row.put("threads", 1);
                row.put("warmupIterations", 2);
                row.put("measurementIterations", 3);
                row.put("warmupTime", "1 s");
                row.put("measurementTime", "1 s");
                row.put("warmupBatchSize", 1);
                row.put("measurementBatchSize", 1);
                row.put("jvmArgs", List.of("-Xms2g", "-Xmx2g"));
                row.put(
                        "primaryMetric",
                        Map.of(
                                "score",
                                12.5,
                                "scoreUnit",
                                "ns/op",
                                "rawData",
                                List.of(List.of(12.0, 12.5, 13.0), List.of(12.0, 12.5, 13.0))));
                row.put(
                        "secondaryMetrics",
                        Map.of(
                                "gc.alloc.rate.norm",
                                Map.of(
                                        "score",
                                        64.0,
                                        "scoreUnit",
                                        "B/op",
                                        "rawData",
                                        List.of(
                                                List.of(64.0, 64.0, 64.0),
                                                List.of(64.0, 64.0, 64.0)))));
                rows.add(row);
            }
        }
        return rows;
    }

    @Test
    public void allEightParameterIdentitiesAndUnitsSurvive() {
        var bmf = BenchmarkReportTask.convert(report(), "analyzeCold");
        assertThat(bmf).hasSize(8);
        assertThat(bmf.keySet().toString()).contains("pairCount", "4096", "distribution", "nested");
        assertThat(bmf.values().iterator().next().toString())
                .contains("allocation_bop", "64.0", "latency", "12.5");
    }

    @Test
    public void missingCaseCannotPassCoverage() {
        var rows = report();
        rows.remove(0);
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("missing parameterized cases");
    }

    @Test
    public void duplicateCaseCannotMergeIntoAnotherMetric() {
        var rows = report();
        rows.add(rows.get(0));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("duplicate");
    }

    @Test
    public void allocationUnitsCannotBeSilentlyReinterpreted() {
        var rows = report();
        rows.get(0)
                .put(
                        "secondaryMetrics",
                        Map.of(
                                "gc.alloc.rate.norm",
                                Map.of(
                                        "score",
                                        64.0,
                                        "scoreUnit",
                                        "MB/sec",
                                        "rawData",
                                        List.of(
                                                List.of(64.0, 64.0, 64.0),
                                                List.of(64.0, 64.0, 64.0)))));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("allocation metric/units");
    }

    @Test
    public void foreignOwnerCannotImpersonateCase() {
        var rows = report();
        rows.get(0).put("benchmark", "foreign.CalculationContractBenchmark.analyzeCold");
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("foreign benchmark owner");
    }

    @Test
    public void smokeGeometryCannotPassPerformanceGate() {
        for (var field : List.of("forks", "threads", "warmupIterations", "measurementIterations")) {
            var rows = report();
            rows.get(0).put(field, 99);
            assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                    .hasMessageContaining("run geometry");
        }
    }

    @Test
    public void missingForkSamplesCannotPass() {
        var rows = report();
        rows.get(0)
                .put(
                        "primaryMetric",
                        Map.of(
                                "score",
                                12.5,
                                "scoreUnit",
                                "ns/op",
                                "rawData",
                                List.of(List.of(12.5))));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("sample geometry");
    }

    @Test
    public void wrongRuntimeAndHeapCannotPass() {
        var rows = report();
        rows.get(0).put("jdkVersion", "21.0.8");
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("JMH/JVM");
        var heapRows = report();
        heapRows.get(0).put("jvmArgs", List.of("-Xms1g", "-Xmx1g"));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(heapRows, "analyzeCold"))
                .hasMessageContaining("heap changed");
    }

    @Test
    public void nonfiniteMeasurementsAreRejected() {
        var rows = report();
        rows.get(0)
                .put(
                        "primaryMetric",
                        Map.of(
                                "score",
                                Double.NaN,
                                "scoreUnit",
                                "ns/op",
                                "rawData",
                                List.of(List.of(1.0, 1.0, 1.0), List.of(1.0, 1.0, 1.0))));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("nonfinite");
    }
}
