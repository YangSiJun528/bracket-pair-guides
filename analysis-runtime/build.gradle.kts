import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    // SDK compile setup only. Ordinary owner jars must stay in lib/ for the
    // classic descriptor and the minimum supported plugin classloader.
    id("org.jetbrains.intellij.platform.base")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

dependencies {
    // Available to compilers; IntelliJ supplies this at runtime in the distribution.
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    api(project(":analysis-model"))
    implementation(project(":analysis-core"))
    api(project(":editor-ui"))
    intellijPlatform {
        intellijIdea("2025.3")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugin("com.intellij.java")
        testBundledPlugin("org.jetbrains.kotlin")
    }
}

// Actual IDE fixtures execute through the plugin-owned official testIde sandbox.
tasks.test { exclude("**/*IdeContractTest.class") }
