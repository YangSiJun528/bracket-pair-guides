package com.sijunyang.buildlogic;

import static org.assertj.core.api.Assertions.assertThat;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Actual plugin registration must export all cases and survive configuration-cache reuse. */
public class BenchmarkReportIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void relativePathsExportAllCasesAndReuseConfigurationCache() throws IOException {
        exportAndReuse(false);
    }

    @Test
    public void absolutePathsExportAllCasesAndReuseConfigurationCache() throws IOException {
        exportAndReuse(true);
    }

    private void exportAndReuse(boolean absolute) throws IOException {
        Path fixture = temporary.newFolder("fixture").toPath();
        Path testKit = temporary.newFolder("test-kit").toPath();
        var owners =
                List.of(
                        "analysis-model",
                        "analysis-core",
                        "editor-ui",
                        "analysis-runtime",
                        "plugin");
        for (String owner : owners) Files.createDirectories(fixture.resolve(owner));
        Files.writeString(
                fixture.resolve("settings.gradle"),
                "rootProject.name = 'report-fixture'\ninclude "
                        + String.join(
                                ", ", owners.stream().map(owner -> "'" + owner + "'").toList())
                        + "\n");
        Files.writeString(
                fixture.resolve("build.gradle"),
                "plugins { id 'base'; id 'bracket.module-boundaries' }\n"
                        + "subprojects { apply plugin: 'java-library' }\n");
        Files.writeString(
                fixture.resolve("gradle.properties"), "org.gradle.configuration-cache=true\n");
        Path input =
                absolute
                        ? temporary.newFolder("external-input").toPath().resolve("jmh.json")
                        : fixture.resolve("input/jmh.json");
        Path output =
                absolute
                        ? temporary.newFolder("external-output").toPath().resolve("bmf.json")
                        : fixture.resolve("output/bmf.json");
        Files.createDirectories(input.getParent());
        var all = new ArrayList<Map<String, Object>>();
        for (String method :
                List.of(
                        "analyzeCold",
                        "analyzeReuse",
                        "visibleQuery",
                        "repair",
                        "cancelledAttempt")) {
            for (var row : new BenchmarkReportTest().report()) {
                var copy = new LinkedHashMap<>(row);
                copy.put(
                        "benchmark",
                        "com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark."
                                + method);
                all.add(copy);
            }
        }
        Files.writeString(input, JsonOutput.toJson(all));
        var arguments =
                List.of(
                        "exportBenchmarkMetrics",
                        "-PbenchmarkResults=" + (absolute ? input : fixture.relativize(input)),
                        "-PbenchmarkBmf=" + (absolute ? output : fixture.relativize(output)),
                        "--configuration-cache-problems=fail",
                        "--no-build-cache",
                        "--stacktrace");
        var first =
                GradleRunner.create()
                        .withProjectDir(fixture.toFile())
                        .withTestKitDir(testKit.toFile())
                        .withPluginClasspath()
                        .withArguments(arguments)
                        .build();
        assertThat(first.task(":exportBenchmarkMetrics").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(first.getOutput()).contains("Configuration cache entry stored.");
        assertMetrics(output);
        String bytes = Files.readString(output);
        // Force the task action to execute from the reused configuration, rather than only checking
        // UP-TO-DATE.
        Files.delete(output);
        var second =
                GradleRunner.create()
                        .withProjectDir(fixture.toFile())
                        .withTestKitDir(testKit.toFile())
                        .withPluginClasspath()
                        .withArguments(arguments)
                        .build();
        assertThat(second.getOutput()).contains("Reusing configuration cache.");
        assertThat(second.task(":exportBenchmarkMetrics").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readString(output)).isEqualTo(bytes);
        assertMetrics(output);
    }

    private void assertMetrics(Path output) {
        var metrics = (Map<?, ?>) new JsonSlurper().parse(output.toFile());
        assertThat(metrics).hasSize(40);
        for (var value : metrics.values()) {
            var measures = (Map<?, ?>) value;
            assertThat(measures.keySet().stream().map(Object::toString).toList())
                    .containsExactlyInAnyOrder("latency", "allocation_bop");
            assertThat(((Number) ((Map<?, ?>) measures.get("latency")).get("value")).doubleValue())
                    .isEqualTo(12.5);
            assertThat(
                            ((Number) ((Map<?, ?>) measures.get("allocation_bop")).get("value"))
                                    .doubleValue())
                    .isEqualTo(64.0);
        }
        for (String method :
                List.of(
                        "analyzeCold",
                        "analyzeReuse",
                        "visibleQuery",
                        "repair",
                        "cancelledAttempt")) {
            long count =
                    metrics.keySet().stream()
                            .filter(
                                    key ->
                                            key.toString()
                                                    .startsWith(
                                                            "com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark."
                                                                    + method
                                                                    + " "))
                            .count();
            assertThat(count).isEqualTo(8);
        }
    }
}
