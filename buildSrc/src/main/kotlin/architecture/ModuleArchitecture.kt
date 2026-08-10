package architecture

import java.io.File

internal class ModuleArchitecture(
    private val policy: ArchitecturePolicy,
    private val layout: ProjectLayout,
    private val actualModules: Set<String>,
    private val configuredDependencies: Set<Triple<String, String, String>>,
    private val settings: File,
) {
    fun inspect(): List<ArchitectureProblem> {
        val problems = mutableListOf<ArchitectureProblem>()
        val expectedModules = policy.moduleDependencies.keys

        (actualModules - expectedModules).sorted().forEach { modulePath ->
            problems += ArchitectureProblem(
                SourceLocation(settings, layout.line(settings, modulePath.removePrefix(":"))),
                "Unknown Gradle module '$modulePath'. Register its architecture dependencies.",
            )
        }
        (expectedModules - actualModules).sorted().forEach { modulePath ->
            problems += ArchitectureProblem(
                SourceLocation(settings, 1),
                "Required Gradle module '$modulePath' is missing.",
            )
        }

        val actualDependencies = configuredDependencies
            .map { dependency -> dependency.first to dependency.second }
            .toSet()
        val expectedDependencies = policy.moduleDependencies
            .flatMap { (source, targets) -> targets.map { target -> source to target } }
            .toSet()

        (actualDependencies - expectedDependencies)
            .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .forEach { (source, target) ->
                val buildFile = layout.moduleBuildFile(source)
                val configurations = configuredDependencies
                    .filter { dependency ->
                        dependency.first == source && dependency.second == target
                    }
                    .map { dependency -> dependency.third }
                    .distinct()
                    .sorted()
                    .joinToString()
                problems += ArchitectureProblem(
                    SourceLocation(buildFile, layout.line(buildFile, "project(\"$target\")")),
                    "Gradle module '$source' may not depend on '$target' " +
                        "(configurations: $configurations).",
                )
            }
        (expectedDependencies - actualDependencies)
            .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .forEach { (source, target) ->
                problems += ArchitectureProblem(
                    SourceLocation(layout.moduleBuildFile(source), 1),
                    "Required Gradle module dependency '$source -> $target' is missing.",
                )
            }

        val moduleNodes = actualModules + expectedModules
        val moduleGraph = actualDependencies
            .map { (source, target) -> Dependency(source, target) }
            .toSet()
        DependencyGraph(moduleNodes, moduleGraph).cycles().forEach { component ->
            actualDependencies
                .filter { (source, target) -> source in component && target in component }
                .forEach { (source, target) ->
                    val buildFile = layout.moduleBuildFile(source)
                    problems += ArchitectureProblem(
                        SourceLocation(
                            buildFile,
                            layout.line(buildFile, "project(\"$target\")"),
                        ),
                        "Gradle module cycle ${component.sorted()} contains $source -> $target.",
                    )
                }
        }

        return problems
    }
}
