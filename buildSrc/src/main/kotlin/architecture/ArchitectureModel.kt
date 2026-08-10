package architecture

import java.io.File

internal data class UseCasePackage(
    val name: String,
    val modulePath: String,
    val packageName: String,
    val allowedDependencies: Set<String>,
)

internal data class ArchitectureSource(
    val modulePath: String,
    val directory: File,
)

internal data class SourceLocation(
    val file: File,
    val line: Int,
)

internal data class Dependency(
    val source: String,
    val target: String,
)

internal data class DependencyEvidence(
    val dependency: Dependency,
    val location: SourceLocation,
)

internal data class ArchitectureProblem(
    val location: SourceLocation,
    val message: String,
)

internal data class ArchitectureResult(
    val problems: List<ArchitectureProblem>,
    val moduleCount: Int,
    val packageDependencyCount: Int,
)

internal data class PackageArchitectureResult(
    val problems: List<ArchitectureProblem>,
    val dependencyCount: Int,
)
