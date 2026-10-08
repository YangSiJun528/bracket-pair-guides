package com.sijunyang.bracketpairguides.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Test

/** Actual references only. Physical compiler visibility is independently checked by TestKit. */
class ResponsibilityBoundaryTest {
    private val production =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.sijunyang.bracketpairguides")

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
    fun uiDoesNotOwnJobs() {
        noClasses()
            .that().resideInAPackage("..ui..")
            .should().dependOnClassesThat(
                matching("coroutine Job") {
                    it.name == "kotlinx.coroutines.Job" || it.name == "kotlinx.coroutines.CompletableJob"
                },
            ).check(production)
    }

    private fun matching(description: String, predicate: (JavaClass) -> Boolean): DescribedPredicate<JavaClass> =
        object : DescribedPredicate<JavaClass>(description) {
            override fun test(input: JavaClass): Boolean = predicate(input)
        }
}
