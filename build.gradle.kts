plugins {
    base
    id("com.diffplug.spotless")
    id("org.jetbrains.kotlin.jvm") apply false
}

spotless {
    kotlin {
        target(
            "plugin/src/main/kotlin/**/*.kt",
            "plugin/src/test/kotlin/**/*.kt",
            "plugin/src/visualTest/kotlin/**/*.kt",
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
            "plugin/*.gradle.kts",
            "benchmarks/*.gradle.kts",
        )
        targetExclude("plugin/src/test/testData/**")
        ktlint("1.8.0")
    }

    java {
        target(
            "plugin/src/main/java/**/*.java",
            "plugin/src/test/java/**/*.java",
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
    dependsOn("spotlessCheck", ":plugin:check", ":benchmarks:jmhJar")
}
