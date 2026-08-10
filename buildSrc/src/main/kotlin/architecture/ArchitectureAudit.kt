package architecture

import java.io.File

internal class ArchitectureAudit(
    root: File,
    private val sources: List<ArchitectureSource>,
    private val actualModules: Set<String>,
    private val configuredModuleDependencies: Set<Triple<String, String, String>>,
    private val settings: File,
    private val policy: ArchitecturePolicy = projectArchitecture,
) {
    private val layout = ProjectLayout(root)

    fun result(): ArchitectureResult {
        val packageResult = PackageArchitecture(policy, layout, sources).inspect()
        val moduleProblems = ModuleArchitecture(
            policy = policy,
            layout = layout,
            actualModules = actualModules,
            configuredDependencies = configuredModuleDependencies,
            settings = settings,
        ).inspect()

        return ArchitectureResult(
            problems = packageResult.problems + moduleProblems,
            moduleCount = actualModules.size,
            packageDependencyCount = packageResult.dependencyCount,
        )
    }
}
