package com.sijunyang.buildlogic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Uses a copy of the real checkout, actual task inputs, and the pinned Kotlin/Java compilers. */
public class CompilationBoundaryTest {
    private static final String PREFIX = "com.sijunyang.bracketpairguides.";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path fixture;
    private static final List<String> BLOCKED_CORE =
            List.of(
                    "core.api.BracketCalculator",
                    "core.input.BracketInput",
                    "core.input.TokenBatch",
                    "core.internal.BracketIndexes",
                    "core.internal.IndexedBracketView",
                    "core.internal.GuidePositionIndex.Builder",
                    "core.internal.SnapshotCalculation");
    private static final List<String> BLOCKED_RUNTIME =
            List.of(
                    "runtime.bootstrap.RuntimeGuideWorkFactory",
                    "runtime.capture.EditorSource",
                    "runtime.capture.BracketTokenCapture");

    @Before
    public void copyRealBuild() throws IOException {
        fixture = temporary.newFolder("checkout").toPath();
        Path source = Path.of(System.getProperty("repository.root"));
        Files.walkFileTree(
                source,
                new java.nio.file.SimpleFileVisitor<>() {
                    private final List<String> excluded =
                            List.of(
                                    ".git",
                                    ".gradle",
                                    ".kotlin",
                                    ".intellijPlatform",
                                    ".qodana",
                                    "build",
                                    "outputs",
                                    ".idea");

                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(
                            Path path, java.nio.file.attribute.BasicFileAttributes attributes)
                            throws IOException {
                        if (!path.equals(source)
                                && excluded.contains(path.getFileName().toString()))
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        Files.createDirectories(fixture.resolve(source.relativize(path)));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            Path path, java.nio.file.attribute.BasicFileAttributes attributes)
                            throws IOException {
                        if (!excluded.contains(path.getFileName().toString()))
                            Files.copy(path, fixture.resolve(source.relativize(path)));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }

    private BuildResult run(boolean fails, String... args) {
        var arguments = new ArrayList<>(List.of(args));
        arguments.add("--stacktrace");
        arguments.add("--no-configuration-cache");
        arguments.add("--no-build-cache");
        var runner =
                GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments);
        return fails ? runner.buildAndFail() : runner.build();
    }

    private void append(String path, String text) throws IOException {
        Files.writeString(
                fixture.resolve(path),
                Files.readString(fixture.resolve(path)) + "\n" + text + "\n");
    }

    private void probe(String owner, String type, boolean kotlin) throws IOException {
        String name = "BoundaryProbe";
        Files.deleteIfExists(fixture.resolve(owner + "/src/main/kotlin/BoundaryProbe.kt"));
        Files.deleteIfExists(fixture.resolve(owner + "/src/main/java/BoundaryProbe.java"));
        Path file =
                fixture.resolve(
                        owner
                                + "/src/main/"
                                + (kotlin ? "kotlin/" : "java/")
                                + name
                                + (kotlin ? ".kt" : ".java"));
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                kotlin
                        ? "val boundaryProbe = " + type + "::class\n"
                        : "final class " + name + " { Class<?> value = " + type + ".class; }\n");
    }

    @Test
    public void actualUiCompilersAcceptModelButCannotSeeCoreOrRuntime() throws IOException {
        for (boolean kotlin : List.of(false, true)) {
            String task = ":editor-ui:compile" + (kotlin ? "Kotlin" : "Java");
            probe("editor-ui", PREFIX + "model.OffsetRange", kotlin);
            run(false, task, "--rerun-tasks");
            var blockedSymbols = new ArrayList<String>(BLOCKED_CORE);
            blockedSymbols.addAll(BLOCKED_RUNTIME);
            for (String relative : blockedSymbols) {
                String blocked = PREFIX + relative;
                probe("editor-ui", blocked, kotlin);
                BuildResult failed = run(true, task, "--rerun-tasks");
                assertProbeDiagnostic(failed, blocked, kotlin);
            }
        }
    }

    @Test
    public void ownerPositiveControlsProveSymbolsExistAndJavaCanSeeThem() throws IOException {
        for (String owner : List.of("analysis-core", "analysis-runtime")) {
            List<String> symbols = owner.equals("analysis-core") ? BLOCKED_CORE : BLOCKED_RUNTIME;
            for (boolean kotlin : List.of(false, true)) {
                probe(owner, PREFIX + symbols.get(0), kotlin);
                Path path =
                        fixture.resolve(
                                owner
                                        + "/src/main/"
                                        + (kotlin
                                                ? "kotlin/BoundaryProbe.kt"
                                                : "java/BoundaryProbe.java"));
                var contents = new StringBuilder(kotlin ? "" : "final class BoundaryProbe {\n");
                for (int i = 0; i < symbols.size(); i++) {
                    String type = PREFIX + symbols.get(i);
                    contents.append(
                            kotlin
                                    ? "private val boundaryProbe"
                                            + i
                                            + ": kotlin.reflect.KClass<*> = "
                                            + type
                                            + "::class\n"
                                    : "Class<?> value" + i + " = " + type + ".class;\n");
                }
                if (!kotlin) contents.append("}\n");
                Files.writeString(path, contents);
                run(
                        false,
                        ":" + owner + ":compile" + (kotlin ? "Kotlin" : "Java"),
                        "--rerun-tasks");
            }
        }
    }

    @Test
    public void actualPluginCompilersCannotSeeCore() throws IOException {
        for (boolean kotlin : List.of(false, true)) {
            probe("plugin", PREFIX + "core.api.BracketCalculator", kotlin);
            assertProbeDiagnostic(
                    run(true, ":plugin:compile" + (kotlin ? "Kotlin" : "Java"), "--rerun-tasks"),
                    PREFIX + "core.api.BracketCalculator",
                    kotlin);
        }
    }

    private void assertProbeDiagnostic(BuildResult failed, String symbol, boolean kotlin) {
        String suffix = kotlin ? "kt" : "java";
        String owner =
                symbol.contains(".core.")
                        ? "core"
                        : symbol.contains(".runtime.") ? "runtime" : "intellij";
        var diagnostic =
                java.util.regex.Pattern.compile(
                                "(?im)^[^\r\n]*BoundaryProbe\\."
                                        + suffix
                                        + "[^\r\n]*(?:Unresolved reference|unresolved reference|does not exist|cannot find symbol)[^\r\n]*$")
                        .matcher(failed.getOutput());
        assertThat(diagnostic.find()).as("Compiler must diagnose the actual probe file").isTrue();
        assertThat(diagnostic.group())
                .as("The same compiler diagnostic must name the missing owner")
                .contains(owner);
        assertThat(failed.getTasks())
                .anySatisfy(
                        task -> {
                            assertThat(task.getPath())
                                    .endsWith(kotlin ? ":compileKotlin" : ":compileJava");
                            assertThat(task.getOutcome())
                                    .isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED);
                        });
    }

    @Test
    public void pureOwnersCannotCompileSdkReferencesInEitherLanguage() throws IOException {
        for (String owner : List.of("analysis-model", "analysis-core")) {
            for (boolean kotlin : List.of(false, true)) {
                probe(owner, PREFIX + "model.OffsetRange", kotlin);
                run(
                        false,
                        ":" + owner + ":compile" + (kotlin ? "Kotlin" : "Java"),
                        "--rerun-tasks");
                probe(owner, "com.intellij.openapi.editor.Editor", kotlin);
                assertProbeDiagnostic(
                        run(
                                true,
                                ":" + owner + ":compile" + (kotlin ? "Kotlin" : "Java"),
                                "--rerun-tasks"),
                        "com.intellij.openapi.editor.Editor",
                        kotlin);
                Files.deleteIfExists(fixture.resolve(owner + "/src/main/kotlin/BoundaryProbe.kt"));
                Files.deleteIfExists(fixture.resolve(owner + "/src/main/java/BoundaryProbe.java"));
            }
        }
    }

    private void rejectAndRestore(String file, String contamination, String reason)
            throws IOException {
        rejectAndRestore(file, contamination, reason, true);
    }

    private void rejectAndRestore(
            String file, String contamination, String reason, boolean inspectWithoutCompilation)
            throws IOException {
        run(false, "verifyProductionModules");
        String original = Files.readString(fixture.resolve(file));
        // Keep producer tasks in the real graph: Kotlin friendPathsSet is a finalized
        // output provider and cannot legally be queried when its producer is -x excluded.
        // Existing clean compiled bytes are retained; only mutated compiler execution is
        // disabled, so malformed flags cannot mask the actual-input policy diagnostic.
        Path init =
                temporary
                        .newFile("skip-mutated-compilation-" + System.nanoTime() + ".gradle")
                        .toPath();
        Files.writeString(
                init,
                "allprojects { tasks.configureEach { t -> "
                        + "if (t.name in ['compileJava', 'compileKotlin']) { t.onlyIf { false } } } }\n");
        String[] auditArguments =
                inspectWithoutCompilation
                        ? new String[] {"verifyProductionModules", "--init-script", init.toString()}
                        : new String[] {"verifyProductionModules"};
        run(false, auditArguments);
        append(file, contamination);
        assertThat(run(true, auditArguments).getOutput())
                .contains("Module boundary violation:", reason);
        Files.writeString(fixture.resolve(file), original);
        run(false, "verifyProductionModules");
        run(false, "verifyProductionModules"); // warm recheck after restoration
    }

    @Test
    public void unusedDirectDependencyIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "dependencies { implementation(project(\":analysis-core\")) }",
                "forbidden project dependencies");
    }

    @Test
    public void transitiveApiExportIsRejected() throws IOException {
        rejectAndRestore(
                "analysis-runtime/build.gradle.kts",
                "dependencies { api(project(\":analysis-core\")) }",
                "forbidden project dependencies");
    }

    @Test
    public void sharedSourceIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "sourceSets.main { java.srcDir(project(\":analysis-core\").file(\"src/main/kotlin\")) }",
                "editor-ui foreign compiler source");
    }

    @Test
    public void sharedCompilerDestinationIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { destinationDirectory.set(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/java/main\")) }",
                "shared compiler destination");
    }

    @Test
    public void directCompilerClasspathIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { classpath += project(\":analysis-core\").files(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/kotlin/main\")) }",
                "sees analysis-core bytecode");
    }

    @Test
    public void typedJavacSourcepathIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { options.sourcepath = files(\"src/main/java\") }",
                "sourcepath must be explicitly empty");
    }

    @Test
    public void typedBootstrapPathIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { options.bootstrapClasspath = files(\"src/main/kotlin\") }",
                "bootstrap classpath");
    }

    @Test
    public void typedProcessorPathIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { options.annotationProcessorPath = files(\"src/main/kotlin\") }",
                "annotation processor path");
    }

    @Test
    public void javacArgumentInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { options.compilerArgs.add(\"-classpath\") }",
                "javac argument injection");
    }

    @Test
    public void kotlinFriendArgumentInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { compilerOptions.freeCompilerArgs.add(\"-Xfriend-paths=/tmp/foreign-owner\") }",
                "Kotlin argument injection");
    }

    @Test
    public void sourceOnlyArchiveIsRejected() throws IOException {
        Path jar = fixture.resolve("source-only.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("injected/Leak.java"));
            output.write(
                    "package injected; public class Leak {}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { classpath += files(rootProject.file(\"source-only.jar\")) }",
                "source-only compiler archive entry");
    }

    @Test
    public void pureModuleSdkLeakIsRejected() throws IOException {
        rejectAndRestore(
                "analysis-core/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { classpath += project(\":editor-ui\").configurations.compileClasspath.get() }",
                "IntelliJ SDK bytecode");
    }

    @Test
    public void copiedOwnerBytecodeInUnrelatedJarIsRejected() throws IOException {
        run(false, ":analysis-core:classes");
        String entry = "com/sijunyang/bracketpairguides/core/api/BracketCalculator.class";
        Path bytes = fixture.resolve("analysis-core/build/classes/kotlin/main/" + entry);
        try (var output =
                new JarOutputStream(Files.newOutputStream(fixture.resolve("unrelated.jar")))) {
            output.putNextEntry(new JarEntry(entry));
            output.write(Files.readAllBytes(bytes));
            output.closeEntry();
        }
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { classpath += files(rootProject.file(\"unrelated.jar\")) }",
                "sees analysis-core bytecode");
    }

    @Test
    public void effectiveKotlinClasspathInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { libraries.from(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/kotlin/main\")) }",
                "sees analysis-core bytecode");
    }

    @Test
    public void foreignKotlinSourcesAreRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { source(rootProject.file(\"analysis-core/src/main/kotlin\")) }",
                "foreign compiler source");
    }

    @Test
    public void uiTestsCannotGainImplementationVisibility() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "dependencies { testImplementation(project(\":analysis-core\")) }",
                "UI test classpath sees analysis-core");
    }

    @Test
    public void overlappingCompilerDestinationsAreRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileJava\") { destinationDirectory.set(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/kotlin/main/injected\")) }",
                "shared compiler destination");
    }

    @Test
    public void kotlinJavaSourceInjectionIsRejected() throws IOException {
        Path foreign = fixture.resolve("foreign/Foreign.java");
        Files.createDirectories(foreign.getParent());
        Files.writeString(foreign, "final class Foreign {}\n");
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { source(rootProject.file(\"foreign/Foreign.java\")) }",
                "foreign compiler source");
    }

    @Test
    public void lateKotlinArgumentsAreRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { javaClass.methods.single { it.name.startsWith(\"setExecutionTimeFreeCompilerArgs\") }.invoke(this, listOf(\"-Xfriend-paths=/tmp/foreign\")) }",
                "execution-time Kotlin argument injection");
    }

    @Test
    public void kotlinCompilerPluginInjectionIsRejected() throws IOException {
        String mutation =
                "buildscript { dependencies { classpath(\"org.jetbrains.kotlin:kotlin-serialization:2.3.21\") } }; apply(plugin = \"org.jetbrains.kotlin.plugin.serialization\")\n";
        rejectAndRestore(
                "editor-ui/build.gradle.kts", mutation, "compiler plugin injection", false);
    }

    @Test
    public void typedKotlinFriendPathsAreRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { friendPaths.from(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/kotlin/main\")) }",
                "production friend paths");
    }

    @Test
    public void commonSourceSetInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") { (javaClass.methods.single { it.name.startsWith(\"getCommonSourceSet\") }.invoke(this) as org.gradle.api.file.ConfigurableFileCollection).from(rootProject.file(\"analysis-core/src/main/kotlin\")) }",
                "foreign compiler source");
    }

    @Test
    public void missingProductionOwnerIsRejected() throws IOException {
        rejectAndRestore(
                "settings.gradle.kts",
                "project(\":analysis-runtime\").name = \"missing-runtime-owner\"",
                "production owner set");
    }

    @Test
    public void actualUiTestJavaClasspathInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<JavaCompile>(\"compileTestJava\") { classpath += project(\":analysis-core\").files(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/kotlin/main\")) }",
                "UI test classpath sees analysis-core");
    }

    @Test
    public void actualUiTestKotlinClasspathInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileTestKotlin\") { libraries.from(project(\":analysis-runtime\").layout.buildDirectory.dir(\"classes/kotlin/main\")) }",
                "UI test classpath sees analysis-runtime");
    }

    @Test
    public void actualUiTestFriendInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileTestKotlin\") { friendPaths.from(project(\":analysis-core\").layout.buildDirectory.dir(\"classes/kotlin/main\")) }",
                "UI test foreign friend path");
    }

    @Test
    public void actualUiTestSourceInjectionIsRejected() throws IOException {
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileTestKotlin\") { source(rootProject.file(\"analysis-core/src/main/kotlin\")) }",
                "UI test foreign compiler source");
    }

    @Test
    public void propertyCannotDisableProductionOwners() throws IOException {
        run(false, "verifyProductionModules", "-PpureBuild=true");
        assertThat(
                        Files.readString(
                                fixture.resolve("build/module-verification/compiler-inputs.txt")))
                .contains("analysis-runtime.java", "plugin.java", "editor-ui.java");
    }
}
