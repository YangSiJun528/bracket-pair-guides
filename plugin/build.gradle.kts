import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

changelog {
    path.set(rootProject.file("CHANGELOG.md").canonicalPath)
}

base {
    archivesName.set(rootProject.name)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // IntelliJ Platform 2024.1 bundles Kotlin stdlib 1.9.22.
        languageVersion = KotlinVersion.KOTLIN_1_9
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}

intellijPlatform {
    projectName = rootProject.name
    buildSearchableOptions = false

    pluginConfiguration {
        // Keep the published minimum stable when the test fixture is upgraded.
        ideaVersion {
            sinceBuild = "241"
        }
    }
    pluginVerification {
        // Resolve JetBrains' recommended cross-version verification matrix.
        ides {
            recommended()
            // The recommended set follows the 2024.1 fixture and currently
            // stops at 2025.2. Also cover the open-ended descriptor's current
            // IntelliJ Platform endpoint without downloading every product.
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
        failureLevel.set(
            listOf(
                FailureLevel.COMPATIBILITY_PROBLEMS,
                FailureLevel.INTERNAL_API_USAGES,
                FailureLevel.OVERRIDE_ONLY_API_USAGES,
                FailureLevel.NON_EXTENDABLE_API_USAGES,
                FailureLevel.MISSING_DEPENDENCIES,
                FailureLevel.INVALID_PLUGIN,
            ),
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.tngtech.archunit:archunit-junit4:1.5.0")

    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")

        // Required only by language-aware lexer integration tests.
        testBundledPlugin("com.intellij.java")
        testBundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
    }
}
