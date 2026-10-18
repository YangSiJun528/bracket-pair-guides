package com.sijunyang.bracketpairguides.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.Test

/** Package call paths; execution threads are checked by the real IDE contracts. */
class ResponsibilityBoundaryTest {
    @Test
    fun productionPackagesFollowModuleDirection() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Model").definedBy("com.sijunyang.bracketpairguides.model..")
            .layer("Core").definedBy("com.sijunyang.bracketpairguides.core..")
            .layer("UI").definedBy("com.sijunyang.bracketpairguides.ui..")
            .layer("Runtime").definedBy("com.sijunyang.bracketpairguides.runtime..")
            .layer("Plugin").definedBy("com.sijunyang.bracketpairguides.plugin..")
            .whereLayer("Model").mayNotAccessAnyLayer()
            .whereLayer("Core").mayOnlyAccessLayers("Model")
            .whereLayer("UI").mayOnlyAccessLayers("Model")
            .whereLayer("Runtime").mayOnlyAccessLayers("Model", "Core", "UI")
            .whereLayer("Plugin").mayOnlyAccessLayers("Model", "UI", "Runtime")
            .check(production)
    }

    @Test
    fun runtimeIsEnteredOnlyThroughBootstrap() {
        noClasses()
            .that().resideOutsideOfPackage("..runtime..")
            .should().dependOnClassesThat(
                matching("runtime implementation outside bootstrap") {
                    it.name.startsWith("com.sijunyang.bracketpairguides.runtime.") &&
                        !it.name.startsWith("com.sijunyang.bracketpairguides.runtime.bootstrap.")
                },
            ).check(production)
    }

    @Test
    fun onlyRuntimeSessionsEnterCalculation() {
        noClasses()
            .that().resideOutsideOfPackages("..core..", "..runtime.session..")
            .should().dependOnClassesThat().resideInAPackage("..core.api..")
            .check(production)
    }

    @Test
    fun modelAndCoreHaveNoPlatformDependency() {
        noClasses()
            .that().resideInAnyPackage("..model..", "..core..")
            .should().dependOnClassesThat().resideInAPackage("com.intellij..")
            .check(production)
    }

    @Test
    fun runtimeUsesOnlyUiOwnedWorkContracts() {
        noClasses()
            .that().resideInAPackage("..runtime..")
            .should().dependOnClassesThat(
                matching("UI implementation outside work contracts") {
                    it.name.startsWith("com.sijunyang.bracketpairguides.ui.") &&
                        !it.name.startsWith("com.sijunyang.bracketpairguides.ui.work.")
                },
            ).check(production)
    }

    @Test
    fun coreImplementationIsNotUsedOutsideItsOwner() {
        noClasses()
            .that().resideOutsideOfPackage("..core..")
            .should().dependOnClassesThat().resideInAPackage("..core.internal..")
            .check(production)
    }

    @Test
    fun uiPoliciesHaveNoPlatformOrCoroutineDependency() {
        noClasses()
            .that().resideInAPackage("..ui.policy..")
            .should().dependOnClassesThat(
                matching("platform or coroutine") {
                    it.name.startsWith("com.intellij.") || it.name.startsWith("kotlinx.coroutines.")
                },
            ).check(production)
    }

    @Test
    fun uiDoesNotScheduleCoroutines() {
        noClasses()
            .that().resideInAPackage("..ui..")
            .should().dependOnClassesThat().resideInAPackage("kotlinx.coroutines..")
            .check(production)
    }

    private fun matching(description: String, predicate: (JavaClass) -> Boolean): DescribedPredicate<JavaClass> =
        object : DescribedPredicate<JavaClass>(description) {
            override fun test(input: JavaClass): Boolean = predicate(input)
        }

    companion object {
        private val production =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sijunyang.bracketpairguides")
    }
}
