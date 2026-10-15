plugins {
    groovy
}
group = "com.sijunyang.buildlogic"
repositories { mavenCentral() }
dependencies {
    compileOnly(gradleApi())
    implementation(localGroovy())
    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

tasks.test {
    dependsOn(tasks.jar)
    systemProperty("buildlogic.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
