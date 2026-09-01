import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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

jmh {
    jmhVersion = "1.37"
    benchmarkMode = listOf("avgt")
    timeUnit = "ms"
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
            .file("reports/jmh/human.txt")
            .get()
            .asFile
    resultsFile =
        layout.buildDirectory
            .file("reports/jmh/results.json")
            .get()
            .asFile

    if (smokeRun.get()) {
        includes = listOf(".*LongArraySort.*Benchmark")
        warmupIterations = 0
        iterations = 1
        timeOnIteration = "100ms"
        fork = 1
    } else if (benchmarkInclude.isPresent) {
        includes = listOf(benchmarkInclude.get())
    }
}
