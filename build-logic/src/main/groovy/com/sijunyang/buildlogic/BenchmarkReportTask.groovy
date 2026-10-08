package com.sijunyang.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Unit-preserving BMF adapter only. JMH runs measurements; Bencher applies history thresholds. */
abstract class BenchmarkReportTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getInputFile()
    @OutputFile abstract RegularFileProperty getOutputFile()
    @Input abstract Property<String> getJob()

    @TaskAction void export() {
        def metrics = convert(new JsonSlurper().parse(inputFile.get().asFile), job.get())
        def target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.text = JsonOutput.prettyPrint(JsonOutput.toJson(metrics)) + '\n'
    }

    static Map convert(Object input, String job) {
        def jobs = ['analyzeCold', 'analyzeReuse', 'visibleQuery', 'repair', 'cancelledAttempt']
        check(job == 'all' || job in jobs, 'unknown benchmark job')
        check(input instanceof List && !input.empty, 'empty JMH report')
        def expected = [] as Set
        (job == 'all' ? jobs : [job]).each { method ->
            ['64', '4096'].each { size ->
                ['siblings', 'nested', 'sparse', 'malformed'].each { distribution ->
                    expected.add("${method}|${distribution}|${size}".toString())
                }
            }
        }
        def seen = [] as Set
        def output = [:]
        input.each { row ->
            check(row instanceof Map && row.benchmark instanceof String, 'invalid benchmark row')
            def method = row.benchmark.tokenize('.').last()
            check(row.benchmark == 'com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark.' + method, 'foreign benchmark owner')
            check(row.jmhVersion == '1.37' && row.jdkVersion instanceof String && row.jdkVersion ==~ /17(?:\..*)?/, 'JMH/JVM contract changed')
            check(row.forks == 2 && row.threads == 1 && row.warmupIterations == 2 && row.measurementIterations == 3 &&
                row.warmupTime == '1 s' && row.measurementTime == '1 s' && row.warmupBatchSize == 1 && row.measurementBatchSize == 1,
                'JMH run geometry changed')
            check(row.jvmArgs instanceof List && row.jvmArgs.count { it.toString().startsWith('-Xms') } == 1 &&
                row.jvmArgs.count { it.toString().startsWith('-Xmx') } == 1 && row.jvmArgs.containsAll(['-Xms2g', '-Xmx2g']), 'JMH heap changed')
            check(sampleGeometry(row.primaryMetric?.rawData) && sampleGeometry(row.secondaryMetrics?.get('gc.alloc.rate.norm')?.rawData), 'JMH sample geometry changed')
            check(row.params instanceof Map && row.params.keySet() == (['distribution', 'pairCount'] as Set), 'parameter contract changed')
            def key = "${method}|${row.params.distribution}|${row.params.pairCount}".toString()
            check(expected.contains(key) && seen.add(key), 'missing/duplicate/unexpected case ' + key)
            check(row.mode == 'avgt' && row.primaryMetric?.scoreUnit == 'ns/op', 'latency units changed')
            def allocations = row.secondaryMetrics?.get('gc.alloc.rate.norm')
            check(allocations?.scoreUnit == 'B/op', 'allocation metric/units missing')
            def ns = finite(row.primaryMetric.score)
            def bytes = finite(allocations.score)
            check(ns >= 0 && bytes >= 0, 'negative metric')
            def name = row.benchmark + ' ' + JsonOutput.toJson(new TreeMap(row.params))
            check(!output.containsKey(name), 'duplicate benchmark identity')
            output[name] = [latency: [value: ns], allocation_bop: [value: bytes]]
        }
        check(seen == expected, 'missing parameterized cases')
        output
    }

    private static boolean sampleGeometry(Object samples) {
        samples instanceof List && samples.size() == 2 && samples.every { fork ->
            fork instanceof List && fork.size() == 3 && fork.every { it instanceof Number && Double.isFinite(it.doubleValue()) && it >= 0 }
        }
    }
    private static double finite(Object score) {
        check(score instanceof Number && Double.isFinite(score.doubleValue()), 'nonfinite/non-numeric metric')
        score.doubleValue()
    }
    private static void check(boolean valid, String reason) {
        if (!valid) throw new GradleException('Benchmark contract violation: ' + reason)
    }
}
