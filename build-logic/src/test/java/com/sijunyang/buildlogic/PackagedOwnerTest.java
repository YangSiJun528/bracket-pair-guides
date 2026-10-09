package com.sijunyang.buildlogic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.gradle.api.Project;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Byte identity is a separate contract from names being present in the release. */
public class PackagedOwnerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Project root;
    private Project owner;
    private Path ownerJar;
    private final java.util.List<Project> owners = new java.util.ArrayList<>();
    private final java.util.Map<String, Path> otherJars = new java.util.LinkedHashMap<>();
    private Path archive;

    @Before
    public void setup() throws IOException {
        root = ProjectBuilder.builder().withProjectDir(temporary.newFolder("root")).build();
        owner = ProjectBuilder.builder().withName("plugin").withParent(root).build();
        Files.createDirectories(root.file("licenses").toPath());
        Path artifacts = temporary.newFolder("artifacts").toPath();
        owner.getPluginManager().apply("java-library");
        var jarTask = owner.getTasks().named("jar", Jar.class).get();
        writeInput(owner, "com/sijunyang/bracketpairguides/plugin/Owner.class");
        jarTask.getDestinationDirectory().set(artifacts.toFile());
        jarTask.getArchiveFileName().set("owner.jar");
        ownerJar = jarTask.getArchiveFile().get().getAsFile().toPath();
        var archiveTask = owner.getTasks().create("buildPlugin", Zip.class);
        archiveTask.getDestinationDirectory().set(artifacts.toFile());
        archiveTask.getArchiveFileName().set("release.zip");
        archive = archiveTask.getArchiveFile().get().getAsFile().toPath();
        writeJar(ownerJar, new byte[] {1, 2, 3});
        owners.add(owner);
        for (String name :
                List.of("analysis-model", "analysis-core", "editor-ui", "analysis-runtime")) {
            var other = ProjectBuilder.builder().withName(name).withParent(root).build();
            owners.add(other);
            other.getPluginManager().apply("java-library");
            var task = other.getTasks().named("jar", Jar.class).get();
            writeInput(other, name + "/Owner.class");
            task.getDestinationDirectory().set(artifacts.toFile());
            task.getArchiveFileName().set(name + ".jar");
            Path file = task.getArchiveFile().get().getAsFile().toPath();
            try (var jar = new JarOutputStream(Files.newOutputStream(file))) {
                jar.putNextEntry(new JarEntry(name + "/Owner.class"));
                jar.write(new byte[] {1, 2, 3});
                jar.closeEntry();
            }
            otherJars.put(name, file);
        }
    }

    private static void writeInput(Project project, String entry) throws IOException {
        Path path =
                project.getLayout()
                        .getBuildDirectory()
                        .get()
                        .getAsFile()
                        .toPath()
                        .resolve("classes/java/main/" + entry);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {1, 2, 3});
    }

    private void mutateOwnerJar(String remove, String add, byte[] content) throws IOException {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        try (var jar = new java.util.jar.JarFile(ownerJar.toFile())) {
            var iterator = jar.entries();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                if (!entry.getName().equals(remove))
                    entries.put(entry.getName(), jar.getInputStream(entry).readAllBytes());
            }
        }
        if (add != null) entries.put(add, content);
        try (var jar = new JarOutputStream(Files.newOutputStream(ownerJar))) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private static void writeJar(Path path, byte[] bytes) throws IOException {
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("com/sijunyang/bracketpairguides/plugin/Owner.class"));
            jar.write(bytes);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/plugin.xml"));
            jar.write(
                    ("<idea-plugin><id>com.sijunyang.bracketpairguides</id><idea-version since-build='241'/>"
                                    + "<extensions><applicationService serviceImplementation='com.sijunyang.bracketpairguides.plugin.Owner'/></extensions></idea-plugin>")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            for (String icon : List.of("META-INF/pluginIcon.svg", "META-INF/pluginIcon_dark.svg")) {
                jar.putNextEntry(new JarEntry(icon));
                jar.write("<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }

    private void release(Path first, boolean duplicate) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("plugin/lib/owner.jar"));
            zip.write(Files.readAllBytes(first));
            zip.closeEntry();
            for (var extra : otherJars.entrySet()) {
                zip.putNextEntry(new ZipEntry("plugin/lib/" + extra.getValue().getFileName()));
                zip.write(Files.readAllBytes(extra.getValue()));
                zip.closeEntry();
            }
            if (duplicate) {
                zip.putNextEntry(new ZipEntry("plugin/lib/duplicate.jar"));
                zip.write(Files.readAllBytes(first));
                zip.closeEntry();
            }
        }
    }

    @Test
    public void exactOwnerBytesAreAccepted() throws IOException {
        release(ownerJar, false);
        ModuleBoundariesPlugin.verifyArchive(root, owners);
    }

    @Test
    public void staleBytesWithSameClassNameAreRejected() throws IOException {
        Path stale = temporary.newFile("stale.jar").toPath();
        writeJar(stale, new byte[] {1, 2, 4});
        release(stale, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("release jar is not actual owner packaging input");
    }

    @Test
    public void duplicateClassIsRejected() throws IOException {
        release(ownerJar, true);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("unknown/duplicate runtime jar");
    }

    @Test
    public void missingOwnerJarIsRejected() throws IOException {
        otherJars.remove("analysis-core");
        release(ownerJar, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("missing production owner jar");
    }

    @Test
    public void missingIconIsRejected() throws IOException {
        mutateOwnerJar("META-INF/pluginIcon_dark.svg", null, null);
        release(ownerJar, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("missing plugin icons");
    }

    @Test
    public void missingLicenseIsRejected() throws IOException {
        Files.writeString(root.file("licenses/required.txt").toPath(), "Attribution");
        release(ownerJar, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("missing attribution");
    }

    @Test
    public void descriptorCannotRegisterMissingClass() throws IOException {
        String xml =
                "<idea-plugin><id>com.sijunyang.bracketpairguides</id><idea-version since-build='241'/><extensions><applicationService serviceImplementation='com.sijunyang.bracketpairguides.plugin.Missing'/></extensions></idea-plugin>";
        mutateOwnerJar(
                "META-INF/plugin.xml",
                "META-INF/plugin.xml",
                xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        release(ownerJar, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("missing registered implementation");
    }

    @Test
    public void foreignBytecodeInOwnerJarIsRejected() throws IOException {
        mutateOwnerJar(null, "foreign/Injected.class", new byte[] {9});
        release(ownerJar, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners))
                .hasMessageContaining("not a compiled/instrumented input");
    }

    @Test
    public void missingProductionOwnerSetIsRejected() throws IOException {
        release(ownerJar, false);
        assertThatThrownBy(() -> ModuleBoundariesPlugin.verifyArchive(root, owners.subList(0, 4)))
                .hasMessageContaining("exactly five production owners");
    }
}
