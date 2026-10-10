import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    // Available to compilers; IntelliJ supplies this at runtime in the distribution.
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    api(project(":analysis-model"))
}
