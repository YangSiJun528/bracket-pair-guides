package com.sijunyang.buildlogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BenchmarkReportTest {
    private List<Map<String, Object>> report() {
        var rows = new ArrayList<Map<String, Object>>();
        for (String size : List.of("64", "4096")) {
            for (String distribution : List.of("siblings", "nested", "sparse", "malformed")) {
                var row = new LinkedHashMap<String, Object>();
                row.put(
                        "benchmark",
                        "com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark.analyzeCold");
                row.put("params", Map.of("pairCount", size, "distribution", distribution));
                row.put("mode", "avgt");
                row.put("primaryMetric", Map.of("score", 12.5, "scoreUnit", "ns/op"));
                row.put(
                        "secondaryMetrics",
                        Map.of("gc.alloc.rate.norm", Map.of("score", 64.0, "scoreUnit", "B/op")));
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
                        Map.of("gc.alloc.rate.norm", Map.of("score", 64.0, "scoreUnit", "MB/sec")));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("allocation metric/units");
    }

    @Test
    public void nonfiniteMeasurementsAreRejected() {
        var rows = report();
        rows.get(0).put("primaryMetric", Map.of("score", Double.NaN, "scoreUnit", "ns/op"));
        assertThatThrownBy(() -> BenchmarkReportTask.convert(rows, "analyzeCold"))
                .hasMessageContaining("nonfinite");
    }
}
