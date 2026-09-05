import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val visualTestSourceSet = sourceSets.create("visualTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val visualTestImplementation = configurations.getByName(
    visualTestSourceSet.implementationConfigurationName,
)

val visualTestArtifactsDirectory = layout.buildDirectory.dir("visual-test-artifacts")
val visualTestBaselinesDirectory = layout.projectDirectory.dir("src/visualTest/resources/baselines")
val visualTestProjectDirectory = layout.buildDirectory.dir("visual-test-project")
val visualTestJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}
val cleanVisualTestArtifacts = tasks.register<Delete>("cleanVisualTestArtifacts") {
    delete(visualTestArtifactsDirectory, visualTestProjectDirectory)
}
val cleanRecordedVisualTestArtifacts = tasks.register<Delete>("cleanRecordedVisualTestArtifacts") {
    delete(visualTestArtifactsDirectory, visualTestProjectDirectory)
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
        // Keep the declared minimum stable when the test fixture is upgraded.
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
            // IntelliJ Platform releases without downloading every product.
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
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
        testFramework(
            TestFrameworkType.Starter,
            version = "242.26775.15",
            configurationName = "visualTestImplementation",
        )
    }

    add(visualTestImplementation.name, "org.junit.jupiter:junit-jupiter:5.10.2")
    add(visualTestImplementation.name, "org.kodein.di:kodein-di-jvm:7.20.2")
    add(
        visualTestImplementation.name,
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1",
    )
    add(
        visualTestSourceSet.runtimeOnlyConfigurationName,
        "org.junit.platform:junit-platform-launcher:1.10.2",
    )
}

intellijPlatformTesting.testIdeUi.register("visualTest") {
    type = IntelliJPlatformType.IntellijIdeaCommunity
    version = "2024.2.6"

    task {
        description = "Runs deterministic Starter and Driver visual regression tests."
        group = "verification"
        testClassesDirs = visualTestSourceSet.output.classesDirs
        classpath = visualTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        javaLauncher.set(visualTestJavaLauncher)
        maxParallelForks = 1
        systemProperty(
            "visual.test.artifacts.dir",
            visualTestArtifactsDirectory.get().asFile.absolutePath,
        )
        systemProperty(
            "visual.test.baselines.dir",
            visualTestBaselinesDirectory.asFile.absolutePath,
        )
        systemProperty(
            "visual.test.project.dir",
            visualTestProjectDirectory.get().asFile.absolutePath,
        )
        outputs.dir(visualTestArtifactsDirectory)
        outputs.upToDateWhen { false }
        dependsOn(cleanVisualTestArtifacts)
    }
}

intellijPlatformTesting.testIdeUi.register("recordVisualTestBaseline") {
    type = IntelliJPlatformType.IntellijIdeaCommunity
    version = "2024.2.6"

    task {
        description = "Explicitly records visual baselines for the pinned IDE."
        group = "verification"
        testClassesDirs = visualTestSourceSet.output.classesDirs
        classpath = visualTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        javaLauncher.set(visualTestJavaLauncher)
        maxParallelForks = 1
        systemProperty("visual.test.record-baseline", true)
        systemProperty(
            "visual.test.force-baseline-overwrite",
            providers.gradleProperty("forceVisualBaselineOverwrite").orElse("false").get(),
        )
        systemProperty(
            "visual.test.artifacts.dir",
            visualTestArtifactsDirectory.get().asFile.absolutePath,
        )
        systemProperty(
            "visual.test.baselines.dir",
            visualTestBaselinesDirectory.asFile.absolutePath,
        )
        systemProperty(
            "visual.test.project.dir",
            visualTestProjectDirectory.get().asFile.absolutePath,
        )
        outputs.upToDateWhen { false }
        dependsOn(cleanRecordedVisualTestArtifacts)
    }
}
