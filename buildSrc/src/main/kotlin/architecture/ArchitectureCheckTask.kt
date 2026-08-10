package architecture

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Architecture verification has no output artifact")
abstract class ArchitectureCheckTask : DefaultTask() {
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Input
    abstract val sourceRoots: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val modulePaths: SetProperty<String>

    @get:Input
    abstract val moduleDependencies: SetProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleBuildFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val settingsFile: RegularFileProperty

    @TaskAction
    fun verifyArchitecture() {
        val root = rootDirectory.get().asFile
        val sources = sourceRoots.get().map { encodedSource ->
            val (modulePath, relativePath) = encodedSource.split('|', limit = 2)
            ArchitectureSource(modulePath, root.resolve(relativePath))
        }
        val configuredDependencies = moduleDependencies.get().map { encodedDependency ->
            val (source, target, configuration) = encodedDependency.split('|', limit = 3)
            Triple(source, target, configuration)
        }.toSet()

        val result = ArchitectureAudit(
            root = root,
            sources = sources,
            actualModules = modulePaths.get(),
            configuredModuleDependencies = configuredDependencies,
            settings = settingsFile.get().asFile,
        ).result()

        if (result.problems.isNotEmpty()) {
            val layout = ProjectLayout(root)
            val report = result.problems
                .distinct()
                .sortedWith(
                    compareBy<ArchitectureProblem>(
                        { problem -> layout.relativePath(problem.location.file) },
                        { problem -> problem.location.line },
                        ArchitectureProblem::message,
                    ),
                )
                .joinToString(separator = "\n") { problem ->
                    "${layout.relativePath(problem.location.file)}:${problem.location.line}: " +
                        problem.message
                }
            throw GradleException("Architecture verification failed:\n$report")
        }

        logger.lifecycle(
            "Architecture verified: ${result.moduleCount} modules, " +
                "${projectArchitecture.packages.size} use-case packages, " +
                "${result.packageDependencyCount} package dependencies.",
        )
    }
}
