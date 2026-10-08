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
tasks.test { systemProperty("repository.root", rootDir.parentFile.absolutePath) }
