import architecture.ArchitectureCheckTask
import org.gradle.api.artifacts.ProjectDependency

plugins {
    base
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.changelog") apply false
}

val architectureSources = mapOf(
    ":engine" to "engine/src/main",
    ":plugin" to "plugin/src/main",
    ":benchmarks" to "benchmarks/src/jmh",
)

val checkArchitecture = tasks.register<ArchitectureCheckTask>("checkArchitecture") {
    rootDirectory.set(layout.projectDirectory)
    sourceRoots.set(
        architectureSources.map { (modulePath, sourcePath) ->
            "$modulePath|$sourcePath"
        },
    )
    sourceFiles.from(
        architectureSources.values.map { sourcePath ->
            fileTree(sourcePath) {
                include("**/*.java", "**/*.kt")
            }
        },
    )
    modulePaths.set(subprojects.map { project -> project.path })
    moduleDependencies.convention(emptySet())
    moduleBuildFiles.from(subprojects.map { project -> project.buildFile })
    settingsFile.set(layout.projectDirectory.file("settings.gradle.kts"))
}

subprojects {
    val sourceModule = path
    configurations.configureEach {
        val configurationName = name
        dependencies.withType(ProjectDependency::class.java).configureEach {
            val targetModule = path
            checkArchitecture.configure {
                moduleDependencies.add("$sourceModule|$targetModule|$configurationName")
            }
        }
    }

    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(checkArchitecture)
    }
}

tasks.named("check") {
    dependsOn(checkArchitecture)
}
