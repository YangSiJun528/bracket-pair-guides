plugins {
    base
    id("bracket.module-boundaries")
    id("com.diffplug.spotless")
    id("org.jetbrains.kotlin.jvm") apply false
}

spotless {
    kotlin {
        target(
            "*/src/main/kotlin/**/*.kt",
            "*/src/test/kotlin/**/*.kt",
            "plugin/src/visualTest/kotlin/**/*.kt",
            "plugin/src/visualBridge/kotlin/**/*.kt",
        )
        targetExclude("plugin/src/test/testData/**")
        ktlint("1.8.0")
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:mixed-condition-operators"
        }
    }

    kotlinGradle {
        target(
            "*.gradle.kts",
            "*/build.gradle.kts",
            "tools/pure-build/*.gradle.kts",
        )
        targetExclude("plugin/src/test/testData/**")
        ktlint("1.8.0")
    }

    java {
        target(
            "*/src/main/java/**/*.java",
            "*/src/test/java/**/*.java",
            "plugin/src/visualTest/java/**/*.java",
            "benchmarks/src/jmh/java/**/*.java",
        )
        targetExclude("plugin/src/test/testData/**")
        googleJavaFormat("1.36.0").aosp()
    }

    format("misc") {
        target(
            ".editorconfig",
            ".gitignore",
            "*.properties",
            "*.yml",
            ".github/**/*.yml",
        )
        targetExclude("plugin/src/test/testData/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn(
        "spotlessCheck",
        ":analysis-model:check",
        ":analysis-core:check",
        "verifyProductionModules",
        gradle.includedBuild("bracket-guide-build-logic").task(":check"),
    )
    if (findProject(":plugin") != null) {
        dependsOn(
            ":editor-ui:check",
            ":analysis-runtime:check",
            ":plugin:check",
            ":plugin:minimumSdkTests",
            ":benchmarks:jmhJar",
        )
    }
}
