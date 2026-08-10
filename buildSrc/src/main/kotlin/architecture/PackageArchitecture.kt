package architecture

internal class PackageArchitecture(
    private val policy: ArchitecturePolicy,
    private val layout: ProjectLayout,
    private val sources: List<ArchitectureSource>,
) {
    fun inspect(): PackageArchitectureResult {
        val problems = mutableListOf<ArchitectureProblem>()
        val dependencies = mutableListOf<DependencyEvidence>()

        sources.forEach { source ->
            layout.sourceFiles(source).forEach { file ->
                inspectSource(source, file, problems, dependencies)
            }
        }

        val actualDependencies = dependencies.map(DependencyEvidence::dependency).toSet()
        DependencyGraph(policy.packagesByPath.keys, actualDependencies).cycles()
            .forEach { component ->
                val useCases = component
                    .map { packageName -> policy.packagesByPath.getValue(packageName).name }
                    .sorted()
                    .joinToString()
                dependencies
                    .filter { evidence ->
                        evidence.dependency.source in component &&
                            evidence.dependency.target in component
                    }
                    .distinctBy(DependencyEvidence::dependency)
                    .forEach { evidence ->
                        problems += ArchitectureProblem(
                            evidence.location,
                            "Package dependency cycle [$useCases] contains " +
                                "${policy.packagesByPath.getValue(evidence.dependency.source).name} -> " +
                                policy.packagesByPath.getValue(evidence.dependency.target).name,
                        )
                    }
            }

        return PackageArchitectureResult(
            problems = problems,
            dependencyCount = actualDependencies.size,
        )
    }

    private fun inspectSource(
        source: ArchitectureSource,
        file: java.io.File,
        problems: MutableList<ArchitectureProblem>,
        dependencies: MutableList<DependencyEvidence>,
    ) {
        val syntax = SourceSyntax.from(file.readText(), file.extension)
        syntax.issues.forEach { issue ->
            problems += ArchitectureProblem(
                SourceLocation(file, issue.line),
                issue.message,
            )
        }

        if (syntax.packageDeclarations.size != 1) {
            problems += ArchitectureProblem(
                SourceLocation(file, syntax.packageDeclarations.firstOrNull()?.line ?: 1),
                "Expected exactly one package declaration; found " +
                    syntax.packageDeclarations.size,
            )
            return
        }

        val packageDeclaration = syntax.packageDeclarations.single()
        val sourceNode = policy.packagesByPath[packageDeclaration.value]
        if (sourceNode == null) {
            problems += ArchitectureProblem(
                SourceLocation(file, packageDeclaration.line),
                "Unknown project package '${packageDeclaration.value}'. " +
                    "Register its use case and edges.",
            )
            return
        }
        if (sourceNode.modulePath != source.modulePath) {
            problems += ArchitectureProblem(
                SourceLocation(file, packageDeclaration.line),
                "${sourceNode.name} belongs to ${sourceNode.modulePath}, not ${source.modulePath}",
            )
        }

        syntax.imports.forEach { importStatement ->
            inspectImport(
                file = file,
                packageName = packageDeclaration.value,
                sourceNode = sourceNode,
                importStatement = importStatement,
                problems = problems,
                dependencies = dependencies,
            )
        }
    }

    private fun inspectImport(
        file: java.io.File,
        packageName: String,
        sourceNode: UseCasePackage,
        importStatement: SourceStatement,
        problems: MutableList<ArchitectureProblem>,
        dependencies: MutableList<DependencyEvidence>,
    ) {
        var importedName = importStatement.value.removeSuffix(";").trim()
        if (importedName.startsWith("static ")) {
            importedName = importedName.removePrefix("static ").trim()
        }
        importedName = importedName
            .replace(Regex("\\s+as\\s+.+$"), "")
            .replace("`", "")
            .trim()

        if (!importedName.startsWith("$PROJECT_PACKAGE_PREFIX.")) return
        if (importedName.endsWith(".*")) {
            problems += ArchitectureProblem(
                SourceLocation(file, importStatement.line),
                "Project wildcard imports are forbidden: '$importedName'.",
            )
            return
        }

        val targetNode = policy.packages
            .filter { node ->
                importedName == node.packageName ||
                    importedName.startsWith("${node.packageName}.")
            }
            .maxByOrNull { node -> node.packageName.length }
        if (targetNode == null) {
            problems += ArchitectureProblem(
                SourceLocation(file, importStatement.line),
                "Import references an unknown project package: '$importedName'.",
            )
            return
        }
        if (targetNode.packageName == packageName) return

        val evidence = DependencyEvidence(
            dependency = Dependency(packageName, targetNode.packageName),
            location = SourceLocation(file, importStatement.line),
        )
        dependencies += evidence
        if (targetNode.name !in sourceNode.allowedDependencies) {
            problems += ArchitectureProblem(
                evidence.location,
                "${sourceNode.name} may not depend on ${targetNode.name}: '$importedName'.",
            )
        }
    }
}
