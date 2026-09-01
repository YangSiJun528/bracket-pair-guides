import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "bracket-pair-guides"

include("plugin", "benchmarks")

pluginManagement {
    plugins {
        id("com.diffplug.spotless") version "8.10.1"
        id("org.jetbrains.kotlin.jvm") version "2.3.0"
        id("me.champeau.jmh") version "0.7.3"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
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
