package com.sijunyang.bracketpairguides.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchUnitRunner;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.runner.RunWith;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@RunWith(ArchUnitRunner.class)
@AnalyzeClasses(
    packages = "com.sijunyang.bracketpairguides",
    importOptions = {
        ImportOption.DoNotIncludeTests.class,
        ArchitectureTest.ProductionBytecode.class,
    }
)
public final class ArchitectureTest {
    private static final String ROOT = "com.sijunyang.bracketpairguides";

    private static final String POLICY = "analysis policy";
    private static final String STATE = "configuration state";
    private static final String WORKBENCH = "editor workbench";
    private static final String HOST = "IntelliJ host";

    @ArchTest
    public static final ArchRule PRODUCTION_DEPENDENCIES_POINT_INWARD_ACROSS_ZONES =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(POLICY).definedBy(
                resideInAPackage(ROOT + ".analysis..")
                    .and(resideOutsideOfPackage(ROOT + ".analysis.intellij.."))
            )
            .layer(STATE).definedBy(ROOT + ".preferences..", ROOT + ".settings")
            .layer(WORKBENCH).definedBy(ROOT + ".presentation..", ROOT + ".editor")
            .layer(HOST).definedBy(
                ROOT + ".analysis.intellij..",
                ROOT + ".editor.events..",
                ROOT + ".editor.highlighting..",
                ROOT + ".settings.ui..",
                ROOT + ".compatibility.."
            )
            .whereLayer(POLICY).mayNotAccessAnyLayer()
            .whereLayer(STATE).mayOnlyAccessLayers(POLICY)
            .whereLayer(WORKBENCH).mayOnlyAccessLayers(POLICY, STATE)
            .whereLayer(HOST).mayOnlyAccessLayers(POLICY, STATE, WORKBENCH)
            .ensureAllClassesAreContainedInArchitecture()
            .because("host integration must depend inward on stable policy and editor state");

    @ArchTest
    public static final ArchRule PRODUCTION_PACKAGE_GRAPH_IS_FREE_OF_CYCLES =
        slices()
            .matching(ROOT + ".(**)")
            .should().beFreeOfCycles()
            .because("feature packages must form a one-way dependency graph");

    @ArchTest
    public static final ArchRule NEUTRAL_ANALYSIS_POLICIES_DO_NOT_DEPEND_ON_INTELLIJ =
        noClasses()
            .that().resideInAnyPackage(
                ROOT + ".analysis.pairing.core",
                ROOT + ".analysis.active",
                ROOT + ".analysis.guide",
                ROOT + ".analysis.sorting",
                ROOT + ".analysis.token"
            )
            .should().dependOnClassesThat().resideInAnyPackage("com.intellij..")
            .because("neutral policy code must not acquire IntelliJ host responsibilities");

    @ArchTest
    public static final ArchRule EDITOR_EVENTS_DO_NOT_DEPEND_ON_ANALYSIS_TYPES =
        noClasses()
            .that().resideInAPackage(ROOT + ".editor.events..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".analysis..")
            .because("events may ask preferences whether analysis changed without knowing analysis types");

    @ArchTest
    public static final ArchRule EDITOR_EVENTS_DO_NOT_CALL_METHODS_RETURNING_ANALYSIS_TYPES =
        noClasses()
            .that().resideInAPackage(ROOT + ".editor.events..")
            .should().callMethodWhere(
                describe(
                    "return an analysis type",
                    call -> resideInAPackage(ROOT + ".analysis..")
                        .test(call.getTarget().getRawReturnType())
                )
            )
            .because("preference queries must keep analysis return types behind their package boundary");

    public static final class ProductionBytecode implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.asURI().toString().contains("/instrumented/instrumentTestCode/");
        }
    }
}
