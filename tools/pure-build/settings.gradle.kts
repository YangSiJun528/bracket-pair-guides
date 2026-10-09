pluginManagement {
    includeBuild("../../build-logic")
    plugins { id("org.jetbrains.kotlin.jvm") version "2.3.21" }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bracket-guides-pure"
include("analysis-model", "analysis-core")
project(":analysis-model").projectDir = file("../../analysis-model")
project(":analysis-core").projectDir = file("../../analysis-core")
dependencyResolutionManagement { repositories { mavenCentral() } }
