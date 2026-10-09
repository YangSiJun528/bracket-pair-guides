plugins {
    groovy
    `java-gradle-plugin`
}
repositories { mavenCentral() }
dependencies {
    implementation(localGroovy())
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
}
gradlePlugin {
    plugins {
        create("moduleBoundaries") {
            id = "bracket.module-boundaries"
            implementationClass = "com.sijunyang.buildlogic.ModuleBoundariesPlugin"
        }
    }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
// Match CompilationBoundaryTest.copyRealBuild: only source/configuration files,
// never recursive build outputs, native caches, or generated verification evidence.
val realBuildFixture = fileTree(rootDir.parentFile) {
    listOf(".git", ".gradle", ".kotlin", ".intellijPlatform", ".qodana", "build", "outputs", ".idea")
        .forEach { excludedName ->
            exclude("**/$excludedName", "**/$excludedName/**")
        }
}

tasks.test {
    systemProperty("repository.root", rootDir.parentFile.absolutePath)
    inputs.files(realBuildFixture)
        .withPropertyName("realBuildFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
