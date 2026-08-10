package architecture

internal const val PROJECT_PACKAGE_PREFIX = "com.sijunyang.bracketpairguides"

internal class ArchitecturePolicy(
    val packages: List<UseCasePackage>,
    val moduleDependencies: Map<String, Set<String>>,
) {
    val packagesByName: Map<String, UseCasePackage> = packages.associateBy(UseCasePackage::name)
    val packagesByPath: Map<String, UseCasePackage> = packages.associateBy(UseCasePackage::packageName)
    val allowedPackageDependencies: Set<Dependency> = packages
        .flatMap { source ->
            source.allowedDependencies.map { targetName ->
                Dependency(
                    source = source.packageName,
                    target = packagesByName.getValue(targetName).packageName,
                )
            }
        }
        .toSet()

    init {
        require(packagesByName.size == packages.size) {
            "Architecture use-case names must be unique"
        }
        require(packagesByPath.size == packages.size) {
            "Architecture packages must be unique"
        }
        packages.forEach { source ->
            val unknownDependencies = source.allowedDependencies - packagesByName.keys
            require(unknownDependencies.isEmpty()) {
                "${source.name} names unknown architecture dependencies: $unknownDependencies"
            }
        }
        require(DependencyGraph(packagesByPath.keys, allowedPackageDependencies).cycles().isEmpty()) {
            "The declared architecture graph must be acyclic"
        }
    }
}

internal val projectArchitecture = ArchitecturePolicy(
    packages = listOf(
        UseCasePackage(
            name = "analysisContracts",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis",
            allowedDependencies = emptySet(),
        ),
        UseCasePackage(
            name = "bracketAnalysis",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.intellij",
            allowedDependencies = setOf(
                "analysisContracts",
                "guidePositions",
                "braceRecognition",
                "snapshotQueries",
            ),
        ),
        UseCasePackage(
            name = "snapshotQueries",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.snapshot",
            allowedDependencies = setOf(
                "analysisContracts",
                "activePairLookup",
                "guidePositions",
                "braceRecognition",
                "neutralPairing",
                "visibleTokenLookup",
            ),
        ),
        UseCasePackage(
            name = "braceRecognition",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.pairing",
            allowedDependencies = setOf("analysisContracts", "neutralPairing"),
        ),
        UseCasePackage(
            name = "guidePositions",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.guide",
            allowedDependencies = setOf("analysisContracts", "neutralPairing"),
        ),
        UseCasePackage(
            name = "activePairLookup",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.active",
            allowedDependencies = setOf("neutralPairing", "primitiveOrdering"),
        ),
        UseCasePackage(
            name = "visibleTokenLookup",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.token",
            allowedDependencies = setOf("neutralPairing", "primitiveOrdering"),
        ),
        UseCasePackage(
            name = "neutralPairing",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.pairing.core",
            allowedDependencies = emptySet(),
        ),
        UseCasePackage(
            name = "primitiveOrdering",
            modulePath = ":engine",
            packageName = "$PROJECT_PACKAGE_PREFIX.analysis.sorting",
            allowedDependencies = emptySet(),
        ),
        UseCasePackage(
            name = "preferences",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.preferences",
            allowedDependencies = setOf("analysisContracts"),
        ),
        UseCasePackage(
            name = "storedSettings",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.settings",
            allowedDependencies = setOf("preferences"),
        ),
        UseCasePackage(
            name = "guidePresentation",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.presentation",
            allowedDependencies = setOf("analysisContracts", "preferences"),
        ),
        UseCasePackage(
            name = "editorSessions",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.editor",
            allowedDependencies = setOf(
                "analysisContracts",
                "guidePresentation",
                "preferences",
            ),
        ),
        UseCasePackage(
            name = "livePreferenceChanges",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.editor.events",
            allowedDependencies = setOf("editorSessions", "preferences", "storedSettings"),
        ),
        UseCasePackage(
            name = "highlightingPass",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.editor.highlighting",
            allowedDependencies = setOf(
                "analysisContracts",
                "editorSessions",
                "livePreferenceChanges",
                "preferences",
                "storedSettings",
            ),
        ),
        UseCasePackage(
            name = "settingsPage",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.settings.ui",
            allowedDependencies = setOf(
                "analysisContracts",
                "livePreferenceChanges",
                "preferences",
                "storedSettings",
            ),
        ),
        UseCasePackage(
            name = "ideCompatibility",
            modulePath = ":plugin",
            packageName = "$PROJECT_PACKAGE_PREFIX.compatibility",
            allowedDependencies = emptySet(),
        ),
        UseCasePackage(
            name = "performanceEvidence",
            modulePath = ":benchmarks",
            packageName = "$PROJECT_PACKAGE_PREFIX.benchmarks",
            allowedDependencies = setOf("neutralPairing", "primitiveOrdering"),
        ),
    ),
    moduleDependencies = mapOf(
        ":engine" to emptySet(),
        ":plugin" to setOf(":engine"),
        ":benchmarks" to setOf(":engine"),
    ),
)
