import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "bracket-pair-guides"

include("analysis-model", "analysis-core", "editor-ui", "analysis-runtime", "plugin", "benchmarks")

pluginManagement {
    includeBuild("build-logic")
    plugins {
        id("com.diffplug.spotless") version "8.10.3"
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("me.champeau.jmh") version "0.7.3"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
