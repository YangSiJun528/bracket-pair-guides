import me.champeau.jmh.ParameterConverter
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

abstract class ListBenchmarkJobs : DefaultTask() {
    @get:Input
    abstract val jobNames: ListProperty<String>

    @TaskAction
    fun listJobs() {
        println(jobNames.get().joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" })
    }
}

abstract class WriteBenchmarkArguments : DefaultTask() {
    @get:Input
    abstract val arguments: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeArguments() {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                arguments.get().joinToString("\n", postfix = "\n") {
                    "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                },
            )
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("me.champeau.jmh")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}

dependencies {
    // Production modules use the Kotlin runtime bundled with IntelliJ. This
    // standalone benchmark process needs its own runtime on the JMH classpath.
    implementation(kotlin("stdlib"))

    // Benchmark the compiled analysis implementation without duplicating it in
    // a separate production module.
    jmhImplementation(project(":plugin"))
}

val smokeRun =
    providers
        .gradleProperty("benchmarkSmoke")
        .map(String::toBoolean)
        .orElse(false)
val benchmarkInclude = providers.gradleProperty("benchmarkInclude")
val benchmarkJob = providers.gradleProperty("benchmarkJob")
val benchmarkJobs = linkedMapOf(
    "sort-pair-events" to "LongArraySortBenchmark",
    "sort-random" to "LongArraySortBenchmark",
    "sort-ascending" to "LongArraySortBenchmark",
    "sort-descending" to "LongArraySortBenchmark",
    "cancellation" to "LongArraySortCancellationBenchmark",
    "pairing" to "PairingMachineBenchmark",
    "preferences" to "PreferenceNormalizationBenchmark",
)
if (benchmarkJob.isPresent) {
    require(benchmarkJob.get() in benchmarkJobs) {
        "Unknown benchmarkJob '${benchmarkJob.get()}'. Choose: ${benchmarkJobs.keys.joinToString()}"
    }
    require(!benchmarkInclude.isPresent) {
        "Use either benchmarkJob or benchmarkInclude, not both."
    }
}
val reportDirectory = if (benchmarkJob.isPresent) "reports/jmh/${benchmarkJob.get()}" else "reports/jmh"
tasks.register<ListBenchmarkJobs>("listBenchmarkJobs") {
    group = "benchmark"
    description = "Prints the benchmark job names as a JSON array."
    jobNames.set(benchmarkJobs.keys.toList())
}

jmh {
    jmhVersion = "1.37"
    benchmarkMode = listOf("avgt")
    warmupIterations = 2
    warmup = "1s"
    iterations = 3
    timeOnIteration = "1s"
    fork = 2
    threads = 1
    failOnError = true
    profilers = listOf("gc")
    resultFormat = "JSON"
    humanOutputFile =
        layout.buildDirectory
            .file("$reportDirectory/human.txt")
            .get()
            .asFile
    resultsFile =
        layout.buildDirectory
            .file("$reportDirectory/results.json")
            .get()
            .asFile

    if (benchmarkJob.isPresent) {
        includes = listOf(".*\\.${benchmarkJobs.getValue(benchmarkJob.get())}\\..*")
        if (benchmarkJob.get().startsWith("sort-")) {
            benchmarkParameters.put(
                "distribution",
                objects.listProperty<String>().value(listOf(benchmarkJob.get().removePrefix("sort-"))),
            )
        }
    } else if (benchmarkInclude.isPresent) {
        includes = listOf(benchmarkInclude.get())
    }

    if (smokeRun.get()) {
        warmupIterations = 0
        iterations = 1
        timeOnIteration = "100ms"
        fork = 1
    }
}

// Export the same JMH options for offline runners: compilation and Gradle startup
// happen before their time-limited measurement job. The selected job gets its
// own bundle and results, without embedding paths from the build machine.
val portableArguments = mutableListOf<String>()
ParameterConverter.collectParameters(jmh, portableArguments)
listOf("-o" to "human.txt", "-rff" to "results.json").forEach { (option, filename) ->
    portableArguments[portableArguments.indexOf(option) + 1] = filename
}
val writeBenchmarkArguments = tasks.register<WriteBenchmarkArguments>("writeBenchmarkArguments") {
    arguments.set(listOf("-jar", "benchmarks.jar") + portableArguments)
    outputFile.set(layout.buildDirectory.file("benchmark-arguments/${benchmarkJob.orElse("all").get()}.args"))
}
tasks.register<Sync>("prepareBenchmarkJob") {
    group = "benchmark"
    description = "Packages the selected benchmarkJob for an offline java @run.args invocation."
    into(layout.buildDirectory.dir("benchmark-jobs/${benchmarkJob.orElse("all").get()}"))
    from(tasks.named("jmhJar")) {
        rename { "benchmarks.jar" }
    }
    from(writeBenchmarkArguments) {
        rename { "run.args" }
    }
}
