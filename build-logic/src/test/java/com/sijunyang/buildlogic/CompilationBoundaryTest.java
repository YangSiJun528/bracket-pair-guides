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
            for (String blocked :
                    List.of(
                            PREFIX + "core.api.BracketCalculator",
                            PREFIX + "core.input.BracketInput",
                            PREFIX + "runtime.bootstrap.RuntimeGuideWorkFactory")) {
                probe("editor-ui", blocked, kotlin);
                BuildResult failed = run(true, task, "--rerun-tasks");
                assertProbeDiagnostic(failed, blocked, kotlin);
            }
        }
    }

    @Test
    public void ownerPositiveControlsProveSymbolsExistAndJavaCanSeeThem() throws IOException {
        for (String type :
                List.of(
                        "core.api.BracketCalculator",
                        "core.input.BracketInput",
                        "core.input.TokenBatch")) {
            for (boolean kotlin : List.of(false, true)) {
                probe("analysis-core", PREFIX + type, kotlin);
                run(
                        false,
                        ":analysis-core:compile" + (kotlin ? "Kotlin" : "Java"),
                        "--rerun-tasks");
            }
        }
        for (boolean kotlin : List.of(false, true)) {
            probe("analysis-runtime", PREFIX + "runtime.bootstrap.RuntimeGuideWorkFactory", kotlin);
            run(false, ":analysis-runtime:compile" + (kotlin ? "Kotlin" : "Java"), "--rerun-tasks");
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
                                "(?s)BoundaryProbe\\."
                                        + suffix
                                        + ".{0,1200}(?:Unresolved reference|unresolved reference|does not exist|cannot find symbol)")
                        .matcher(failed.getOutput());
        assertThat(diagnostic.find()).as("Compiler must diagnose the actual probe file").isTrue();
        assertThat(failed.getOutput()).contains(owner);
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
        run(false, "verifyProductionModules");
        String original = Files.readString(fixture.resolve(file));
        append(file, contamination);
        var skips = new ArrayList<String>();
        skips.add("verifyProductionModules");
        // Inspect mutated effective inputs without unrelated compiler-option errors hiding the
        // policy result.
        for (String owner :
                List.of(
                        "analysis-model",
                        "analysis-core",
                        "editor-ui",
                        "analysis-runtime",
                        "plugin")) {
            skips.add("-x");
            skips.add(":" + owner + ":compileJava");
            skips.add("-x");
            skips.add(":" + owner + ":compileKotlin");
        }
        assertThat(run(true, skips.toArray(String[]::new)).getOutput())
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
                "shared source root");
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
        rejectAndRestore(
                "editor-ui/build.gradle.kts",
                "buildscript { dependencies { classpath(\"org.jetbrains.kotlin:kotlin-serialization:2.3.21\") } }; apply(plugin = \"org.jetbrains.kotlin.plugin.serialization\")",
                "compiler plugin injection");
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
}
