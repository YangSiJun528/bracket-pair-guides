import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    // SDK compile setup only. Ordinary owner jars must stay in lib/ for the
    // classic descriptor and the minimum 241 plugin classloader.
    id("org.jetbrains.intellij.platform.base")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    api(project(":analysis-model"))
    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugin("com.intellij.java")
        testBundledPlugin("org.jetbrains.kotlin")
    }
}

// Actual IDE fixtures execute through the plugin-owned official testIde sandbox.
tasks.test { exclude("**/*IdeContractTest.class") }
