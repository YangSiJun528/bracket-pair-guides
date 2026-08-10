package architecture

import java.io.File

internal class ProjectLayout(
    val root: File,
) {
    fun sourceFiles(source: ArchitectureSource): List<File> {
        if (!source.directory.isDirectory) return emptyList()
        return source.directory
            .walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.extension == "kt" || file.extension == "java" }
            .sortedBy(File::getPath)
            .toList()
    }

    fun relativePath(file: File): String =
        file.relativeToOrSelf(root).invariantSeparatorsPath

    fun line(file: File, text: String): Int {
        if (!file.isFile) return 1
        return file.readLines().indexOfFirst { line -> text in line }
            .takeIf { index -> index >= 0 }
            ?.plus(1)
            ?: 1
    }

    fun moduleBuildFile(modulePath: String): File =
        root.resolve(modulePath.removePrefix(":").replace(':', File.separatorChar))
            .resolve("build.gradle.kts")
}
