package com.sijunyang.bracketpairguides.architecture

import com.tngtech.archunit.base.DescribedPredicate.describe
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.junit.ArchUnitRunner
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.runner.RunWith

private const val ROOT = "com.sijunyang.bracketpairguides"

@RunWith(ArchUnitRunner::class)
@AnalyzeClasses(
    packages = [ROOT],
    importOptions = [
        ImportOption.DoNotIncludeTests::class,
        ArchitectureTest.ProductionBytecode::class,
    ],
)
internal class ArchitectureTest {
    internal class ProductionBytecode : ImportOption {
        override fun includes(location: Location): Boolean =
            "/instrumented/instrumentTestCode/" !in location.asURI().toString()
    }

    internal companion object {
        private const val POLICY = "analysis policy"
        private const val STATE = "configuration state"
        private const val WORKBENCH = "editor workbench"
        private const val HOST = "IntelliJ host"

        @ArchTest
        @JvmField
        val productionDependenciesPointInwardAcrossZones: ArchRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(POLICY).definedBy(
                resideInAPackage("$ROOT.analysis..")
                    .and(resideOutsideOfPackage("$ROOT.analysis.intellij..")),
            )
            .layer(STATE).definedBy("$ROOT.preferences..", "$ROOT.settings")
            .layer(WORKBENCH).definedBy("$ROOT.presentation..", "$ROOT.editor")
            .layer(HOST).definedBy(
                "$ROOT.analysis.intellij..",
                "$ROOT.editor.events..",
                "$ROOT.editor.highlighting..",
                "$ROOT.settings.ui..",
            )
            .whereLayer(POLICY).mayNotAccessAnyLayer()
            .whereLayer(STATE).mayOnlyAccessLayers(POLICY)
            .whereLayer(WORKBENCH).mayOnlyAccessLayers(POLICY, STATE)
            .whereLayer(HOST).mayOnlyAccessLayers(POLICY, STATE, WORKBENCH)
            .ensureAllClassesAreContainedInArchitecture()
            .because("host integration must depend inward on stable policy and editor state")

        @ArchTest
        @JvmField
        val productionPackageGraphIsFreeOfCycles: ArchRule = slices()
            .matching("$ROOT.(**)")
            .should().beFreeOfCycles()
            .because("feature packages must form a one-way dependency graph")

        @ArchTest
        @JvmField
        val neutralAnalysisPoliciesDoNotDependOnIntellij: ArchRule = noClasses()
            .that().resideInAnyPackage(
                "$ROOT.analysis.pairing.core",
                "$ROOT.analysis.active",
                "$ROOT.analysis.guide",
                "$ROOT.analysis.sorting",
                "$ROOT.analysis.token",
            )
            .should().dependOnClassesThat().resideInAnyPackage("com.intellij..")
            .because("neutral policy code must not acquire IntelliJ host responsibilities")

        @ArchTest
        @JvmField
        val editorEventsDoNotDependOnAnalysisTypes: ArchRule = noClasses()
            .that().resideInAPackage("$ROOT.editor.events..")
            .should().dependOnClassesThat().resideInAPackage("$ROOT.analysis..")
            .because("events may ask preferences whether analysis changed without knowing analysis types")

        @ArchTest
        @JvmField
        val editorEventsDoNotCallMethodsReturningAnalysisTypes: ArchRule = noClasses()
            .that().resideInAPackage("$ROOT.editor.events..")
            .should().callMethodWhere(
                describe<JavaMethodCall>("return an analysis type") { call ->
                    resideInAPackage("$ROOT.analysis..").test(call.target.rawReturnType)
                },
            )
            .because("preference queries must keep analysis return types behind their package boundary")
    }
}
