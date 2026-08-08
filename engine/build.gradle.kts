import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

@OptIn(ExperimentalAbiValidation::class)
kotlin {
    jvmToolchain(17)
    explicitApi()
    abiValidation {
        enabled.set(true)
    }
    compilerOptions {
        // IntelliJ Platform 2024.1 bundles Kotlin stdlib 1.9.22.
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}

tasks.named("check") {
    dependsOn("checkLegacyAbi", "checkEngineApiPackages")
}

val checkEngineApiPackages by tasks.registering {
    group = "verification"
    description = "Rejects public engine ABI outside the typed analysis.api boundary."

    val abiBaseline = layout.projectDirectory.file("api/engine.api")
    inputs.file(abiBaseline)

    doLast {
        val allowedPrefix = "com/sijunyang/bracketpairguides/analysis/api/"
        val classDeclaration = Regex("^public .* class ([^ :]+)")
        val unexpected = abiBaseline.asFile.readLines()
            .mapNotNull { line -> classDeclaration.matchEntire(line)?.groupValues?.get(1) }
            .filterNot { className -> className.startsWith(allowedPrefix) }

        check(unexpected.isEmpty()) {
            "Public engine ABI must stay under analysis.api; found: " +
                unexpected.joinToString()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // The deployable plugin is a subproject rather than the Gradle root,
        // so this module cannot inherit its target IntelliJ Platform.
        intellijIdeaCommunity("2024.1.7")

        testBundledPlugin("com.intellij.java")
        testBundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }
}
