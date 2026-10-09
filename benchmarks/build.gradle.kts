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
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation(project(":analysis-core"))
    implementation(project(":analysis-model"))
}

val benchmarkJob = providers.gradleProperty("benchmarkJob")
val jobs = setOf("analyzeCold", "analyzeReuse", "visibleQuery", "repair", "cancelledAttempt")
require(!benchmarkJob.isPresent || benchmarkJob.get() in jobs) { "Unknown benchmarkJob" }

jmh {
    jmhVersion = "1.37"
    if (benchmarkJob.isPresent) includes = listOf(".*\\.${benchmarkJob.get()}")
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
    resultsFile = layout.buildDirectory.file("reports/jmh/results.json").get().asFile
    humanOutputFile = layout.buildDirectory.file("reports/jmh/human.txt").get().asFile
    jvmArgs = listOf("-Xms2g", "-Xmx2g")
    if (providers.gradleProperty("benchmarkSmoke").map(String::toBoolean).getOrElse(false)) {
        warmupIterations = 0
        iterations = 1
        timeOnIteration = "100ms"
        fork = 1
    }
}
