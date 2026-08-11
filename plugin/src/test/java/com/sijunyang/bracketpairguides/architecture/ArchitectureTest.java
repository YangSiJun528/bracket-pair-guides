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

    private static final String ANALYSIS = "analysis values";
    private static final String ANALYSIS_INTELLIJ = "IntelliJ analysis";
    private static final String SNAPSHOT = "snapshots";
    private static final String PAIRING = "brace recognition";
    private static final String GUIDE = "guide positions";
    private static final String ACTIVE = "active pairs";
    private static final String TOKEN = "bracket tokens";
    private static final String PAIRING_CORE = "pairing core";
    private static final String SORTING = "cooperative sorting";

    private static final String PREFERENCES = "preferences";
    private static final String SETTINGS = "settings persistence";
    private static final String PRESENTATION = "editor presentation";
    private static final String EDITOR = "editor sessions";
    private static final String EDITOR_EVENTS = "editor events";
    private static final String HIGHLIGHTING = "highlighting lifecycle";
    private static final String SETTINGS_UI = "settings UI";
    private static final String COMPATIBILITY = "IDE compatibility";

    @ArchTest
    public static final ArchRule PRODUCTION_DEPENDENCIES_FOLLOW_THE_DECLARED_PACKAGE_DAG =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(ANALYSIS).definedBy(ROOT + ".analysis")
            .layer(ANALYSIS_INTELLIJ).definedBy(ROOT + ".analysis.intellij")
            .layer(SNAPSHOT).definedBy(ROOT + ".analysis.snapshot")
            .layer(PAIRING).definedBy(ROOT + ".analysis.pairing")
            .layer(GUIDE).definedBy(ROOT + ".analysis.guide")
            .layer(ACTIVE).definedBy(ROOT + ".analysis.active")
            .layer(TOKEN).definedBy(ROOT + ".analysis.token")
            .layer(PAIRING_CORE).definedBy(ROOT + ".analysis.pairing.core")
            .layer(SORTING).definedBy(ROOT + ".analysis.sorting")
            .layer(PREFERENCES).definedBy(ROOT + ".preferences")
            .layer(SETTINGS).definedBy(ROOT + ".settings")
            .layer(PRESENTATION).definedBy(ROOT + ".presentation")
            .layer(EDITOR).definedBy(ROOT + ".editor")
            .layer(EDITOR_EVENTS).definedBy(ROOT + ".editor.events")
            .layer(HIGHLIGHTING).definedBy(ROOT + ".editor.highlighting")
            .layer(SETTINGS_UI).definedBy(ROOT + ".settings.ui")
            .layer(COMPATIBILITY).definedBy(ROOT + ".compatibility")
            .whereLayer(ANALYSIS).mayNotAccessAnyLayer()
            .whereLayer(ANALYSIS_INTELLIJ)
                .mayOnlyAccessLayers(ANALYSIS, GUIDE, PAIRING, PAIRING_CORE, SNAPSHOT)
            .whereLayer(SNAPSHOT)
                .mayOnlyAccessLayers(ANALYSIS, ACTIVE, GUIDE, PAIRING, PAIRING_CORE, TOKEN)
            .whereLayer(PAIRING).mayOnlyAccessLayers(ANALYSIS, PAIRING_CORE)
            .whereLayer(GUIDE).mayOnlyAccessLayers(ANALYSIS, PAIRING_CORE)
            .whereLayer(ACTIVE).mayOnlyAccessLayers(PAIRING_CORE, SORTING)
            .whereLayer(TOKEN).mayOnlyAccessLayers(PAIRING_CORE, SORTING)
            .whereLayer(PAIRING_CORE).mayNotAccessAnyLayer()
            .whereLayer(SORTING).mayNotAccessAnyLayer()
            .whereLayer(PREFERENCES).mayOnlyAccessLayers(ANALYSIS)
            .whereLayer(SETTINGS).mayOnlyAccessLayers(PREFERENCES)
            .whereLayer(PRESENTATION).mayOnlyAccessLayers(ANALYSIS, SNAPSHOT, PREFERENCES)
            .whereLayer(EDITOR).mayOnlyAccessLayers(ANALYSIS, SNAPSHOT, PRESENTATION, PREFERENCES)
            .whereLayer(EDITOR_EVENTS).mayOnlyAccessLayers(EDITOR, PREFERENCES, SETTINGS)
            .whereLayer(HIGHLIGHTING)
                .mayOnlyAccessLayers(
                    ANALYSIS,
                    ANALYSIS_INTELLIJ,
                    SNAPSHOT,
                    EDITOR,
                    EDITOR_EVENTS,
                    PREFERENCES,
                    SETTINGS
                )
            .whereLayer(SETTINGS_UI)
                .mayOnlyAccessLayers(ANALYSIS, PAIRING, EDITOR_EVENTS, PREFERENCES, SETTINGS)
            .whereLayer(COMPATIBILITY).mayNotAccessAnyLayer()
            .ensureAllClassesAreContainedInArchitecture()
            .because("one deployable plugin still needs one-way logical boundaries");

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
