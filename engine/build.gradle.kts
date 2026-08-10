import org.gradle.api.tasks.compile.JavaCompile
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
        filters {
            excluded {
                // Java visibility is required across internal feature packages.
                // These implementation types are not a supported plugin API.
                byNames.add(
                    "com.sijunyang.bracketpairguides.analysis.pairing.core.**",
                )
            }
        }
    }
    compilerOptions {
        // IntelliJ Platform 2024.1 bundles Kotlin stdlib 1.9.22.
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.named("check") {
    dependsOn("checkLegacyAbi", "checkEngineApiPackages")
}

val checkEngineApiPackages by tasks.registering {
    group = "verification"
    description = "Rejects public engine ABI outside the root analysis facade."

    val abiBaseline = layout.projectDirectory.file("api/engine.api")
    inputs.file(abiBaseline)

    doLast {
        val facadePrefix = "com/sijunyang/bracketpairguides/analysis/"
        val isFacadeType: (String) -> Boolean = { className ->
            className.startsWith(facadePrefix) &&
                '/' !in className.removePrefix(facadePrefix)
        }
        val classDeclaration = Regex("^public .* class ([^ :]+)")
        val abiLines = abiBaseline.asFile.readLines()
        val unexpected = abiLines
            .mapNotNull { line -> classDeclaration.matchEntire(line)?.groupValues?.get(1) }
            .filterNot(isFacadeType)

        check(unexpected.isEmpty()) {
            "Public engine ABI must stay directly in the analysis facade; found: " +
                unexpected.joinToString()
        }
        val projectTypeReference = Regex(
            "com/sijunyang/bracketpairguides/([A-Za-z0-9_$/]+)",
        )
        val leakedTypes = abiLines
            .flatMap { line ->
                projectTypeReference.findAll(line).map { match -> match.groupValues[1] }.toList()
            }
            .filterNot { typeName -> isFacadeType("com/sijunyang/bracketpairguides/$typeName") }
            .distinct()

        check(leakedTypes.isEmpty()) {
            "Engine implementation types must not leak through the public analysis facade; " +
                "found: ${leakedTypes.joinToString()}"
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
