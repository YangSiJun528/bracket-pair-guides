import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.TestIdeUiTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val visualBridge = sourceSets.create("visualBridge") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}
val visualTestSourceSet = sourceSets.create("visualTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val visualTestImplementation = configurations.getByName(
    visualTestSourceSet.implementationConfigurationName,
)

// The Driver bridge needs the plugin classloader, but belongs only in UI-test archives.
val visualTestBridgeJar = tasks.register<Jar>("visualTestBridgeJar") {
    archiveClassifier.set("visual-test-bridge")
    from(visualBridge.output)
}
val releasePlugin = tasks.named<BuildPluginTask>("buildPlugin")
val buildVisualTestPlugin = tasks.register<Zip>("buildVisualTestPlugin") {
    description = "Adds the Driver bridge to the release plugin for visual tests and manual QA."
    archiveClassifier.set("visual-test")
    destinationDirectory.set(layout.buildDirectory.dir("visual-test-distributions"))
    from(zipTree(releasePlugin.flatMap { it.archiveFile }))
    from(visualTestBridgeJar) {
        into(releasePlugin.flatMap { it.archiveBaseName }.map { "$it/lib" })
    }
}
// Gradle plugin 2.18.1 marks its UI-test task API incubating. This test-only
// configuration selects the bridge archive and does not enter the release plugin.
@Suppress("UnstableApiUsage")
tasks.withType<TestIdeUiTask>().configureEach {
    archiveFile.set(buildVisualTestPlugin.flatMap { it.archiveFile })
    doFirst {
        systemProperty("visual.test.ide.path", platformPath.toString())
    }
}

val visualTestArtifactsDirectory = layout.buildDirectory.dir("visual-test-artifacts")
val visualTestBaselinesDirectory = layout.projectDirectory.dir("src/visualTest/resources/baselines")
val visualTestProjectDirectory = layout.buildDirectory.dir("visual-test-project")
val visualTestJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}
// The 262 Driver runs on JDK 25; release plugin bytecode remains Java 21.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileVisualTestKotlin") {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}
tasks.named<JavaCompile>("compileVisualTestJava") {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
    options.release.set(25)
}

val cleanVisualTestArtifacts = tasks.register<Delete>("cleanVisualTestArtifacts") {
    delete(visualTestArtifactsDirectory, visualTestProjectDirectory)
}

base {
    archivesName.set(rootProject.name)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ Platform 2025.3 bundles Kotlin stdlib 2.2.20.
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

intellijPlatform {
    projectName = rootProject.name
    buildSearchableOptions = false

    pluginConfiguration {
        // Keep the declared minimum stable when the test fixture is upgraded.
        ideaVersion {
            sinceBuild = "253"
        }
    }
    pluginVerification {
        // Resolve JetBrains' recommended cross-version verification matrix.
        ides {
            recommended()
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
        failureLevel.set(
            listOf(
                FailureLevel.COMPATIBILITY_PROBLEMS,
                FailureLevel.DEPRECATED_API_USAGES,
                FailureLevel.EXPERIMENTAL_API_USAGES,
                FailureLevel.INTERNAL_API_USAGES,
                FailureLevel.OVERRIDE_ONLY_API_USAGES,
                FailureLevel.NON_EXTENDABLE_API_USAGES,
                FailureLevel.MISSING_DEPENDENCIES,
                FailureLevel.INVALID_PLUGIN,
            ),
        )
    }
}

// Distribute the attribution and license for the cancellation-aware native brace scan.
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("licenses")) {
        into("META-INF/licenses")
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (name != "compileVisualTestJava") options.release.set(21)
}

dependencies {
    implementation(project(":analysis-model"))
    implementation(project(":editor-ui"))
    implementation(project(":analysis-runtime"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.tngtech.archunit:archunit-junit4:1.5.1")

    intellijPlatform {
        intellijIdea("2025.3")

        // Required only by language-aware lexer integration tests.
        testBundledPlugin("com.intellij.java")
        testBundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
        testFramework(
            TestFrameworkType.Starter,
            version = "262.10968.63",
            configurationName = "visualTestImplementation",
        )
    }

    add(visualTestSourceSet.runtimeOnlyConfigurationName, "org.jetbrains.teamcity:serviceMessages:2024.07")
    add(visualTestImplementation.name, "org.junit.jupiter:junit-jupiter:6.1.3")
    add(visualTestImplementation.name, "org.kodein.di:kodein-di-jvm:7.33.0")
    add(
        visualTestImplementation.name,
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
    )
    add(
        visualTestSourceSet.runtimeOnlyConfigurationName,
        "org.junit.platform:junit-platform-launcher:6.1.3",
    )
}

listOf("visualTest", "captureVisualTestCandidates").forEach { taskName ->
    val captureCandidates = taskName == "captureVisualTestCandidates"
    intellijPlatformTesting.testIdeUi.register(taskName) {
        type = IntelliJPlatformType.IntellijIdea
        version = "2026.2.3"

        task {
            description = if (captureCandidates) {
                "Captures candidate images for review without accepting or overwriting baselines."
            } else {
                "Runs deterministic Starter and Driver visual regression tests."
            }
            group = "verification"
            testClassesDirs = visualTestSourceSet.output.classesDirs
            classpath = visualTestSourceSet.runtimeClasspath
            useJUnitPlatform { includeEngines("junit-jupiter") }
            filter { excludeTestsMatching("*.ManualQaLauncher") }
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
            if (captureCandidates) {
                systemProperty("visual.test.capture-candidates", true)
            } else {
                outputs.dir(visualTestArtifactsDirectory)
            }
            outputs.upToDateWhen { false }
            dependsOn(cleanVisualTestArtifacts)
        }
    }
}

val sdkOwners = listOf(project(":editor-ui"), project(":analysis-runtime"), project)
listOf("minimumSdkTests" to "2025.3", "currentSdkTests" to "263.6259.32").forEach { (name, selectedVersion) ->
    intellijPlatformTesting.testIde.register(name) {
        type = IntelliJPlatformType.IntellijIdea
        version = selectedVersion
        testFramework(TestFrameworkType.Platform)
        task {
            description = "Runs module-owned actual IDE contracts against $selectedVersion."
            testClassesDirs =
                files(
                    sdkOwners.map {
                        it.extensions.getByType<SourceSetContainer>().getByName("test").output.classesDirs
                    },
                )
            // Preserve the official selected SDK/bootstrap classpath. Owner fixture outputs
            // need no owner SDK/runtime jars: those would shadow the selected IDE.
            classpath += files(sdkOwners.map { it.extensions.getByType<SourceSetContainer>().getByName("test").output })
            filter { includeTestsMatching("*IdeContractTest") }
            maxParallelForks = 1
            systemProperty("contract.ide.baseline", if (name == "minimumSdkTests") "253" else "263")
            systemProperty("contract.ide.version", selectedVersion)
            outputs.upToDateWhen { false }
        }
    }
}

tasks.test { exclude("**/*IdeContractTest.class") }

// A separate opt-in fixture source set; never part of release packaging or ordinary check.
apply(from = "sdk-measurement.gradle")
val sdkMeasurementJar = layout.buildDirectory.file("sdk-measurement/plugin.jar")
val sdkMeasurementEvidence = layout.buildDirectory.file("sdk-measurement/descriptor-evidence.json")
val prepareSdkMeasurementPluginJar = tasks.named("prepareSdkMeasurementPluginJar")
val prepareSdkMeasurementTestResources = tasks.named("prepareSdkMeasurementTestResources")
val sdkPerformance = sourceSets.create("sdkPerformance") {
    compileClasspath += sourceSets.test.get().compileClasspath
    compileClasspath += files(sdkOwners.map { it.extensions.getByType<SourceSetContainer>().getByName("test").output })
}
intellijPlatformTesting.testIde.register("sdkPerformance") {
    sandboxDirectory.set(layout.buildDirectory.dir("sdk-measurement/sandbox"))
    prepareSandboxTask {
        dependsOn(prepareSdkMeasurementPluginJar)
        pluginJar.set(sdkMeasurementJar)
        doLast {
            val installed = pluginDirectory.file("lib/plugin.jar").get().asFile
            require(installed.readBytes().contentEquals(sdkMeasurementJar.get().asFile.readBytes())) {
                "SDK measurement sandbox did not install the isolated descriptor archive"
            }
        }
    }
    type = IntelliJPlatformType.IntellijIdea
    version = "2025.3"
    testFramework(TestFrameworkType.Platform)
    task {
        description = "Runs explicitly selected real SDK comparison measurements; serial main orchestration only."
        dependsOn(prepareSdkMeasurementPluginJar, prepareSdkMeasurementTestResources)
        testClassesDirs = sdkPerformance.output.classesDirs
        // Keep official PathClassLoader/selected IDE bootstrap entries intact.
        classpath +=
            sdkPerformance.output +
            files(sdkOwners.map { it.extensions.getByType<SourceSetContainer>().getByName("test").output })
        val originalMeasurementClasspath = classpath
        val ownResourceRoots = listOfNotNull(
            sourceSets.test.get().output.resourcesDir,
            sdkPerformance.output.resourcesDir,
        )
            .map { it.canonicalFile }.toSet()
        classpath = originalMeasurementClasspath.filter { it.canonicalFile !in ownResourceRoots } +
            files(
                layout.buildDirectory.dir("sdk-measurement/test-resources/test"),
                layout.buildDirectory.dir("sdk-measurement/test-resources/sdkPerformance"),
            )
        filter { includeTestsMatching("*SdkComparisonMeasurementTest") }
        maxParallelForks = 1
        systemProperty("issue97.perf.host", "com.sijunyang.bracketpairguides.comparison.CandidateComparisonHost")
        providers.systemPropertiesPrefixedBy("issue97.perf.").get().forEach { (name, value) ->
            systemProperty(name, value)
        }
        systemProperty("contract.ide.baseline", "253")
        systemProperty("contract.ide.version", "2025.3")
        systemProperty("issue97.perf.descriptorMode", "manual-registry-events-no-auto-startup-pass")
        systemProperty("issue97.perf.descriptorEvidence", sdkMeasurementEvidence.get().asFile.absolutePath)
        systemProperty(
            "issue97.perf.descriptorResourcesEvidence",
            layout.buildDirectory.file("sdk-measurement/test-resources-evidence.json").get().asFile.absolutePath,
        )
        outputs.upToDateWhen { false }
        doFirst {
            require(systemProperties["issue97.perf.workload"] != null) { "Explicit -Dissue97.perf.workload required" }
            require(systemProperties["issue97.perf.output"] != null) { "Explicit -Dissue97.perf.output required" }
        }
    }
}
