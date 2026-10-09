plugins {
    base
    id("bracket.module-boundaries")
}
subprojects {
    // Shared sources/build conventions; never shared compilation output or local project cache.
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(project.name))
}
tasks.named("check") { dependsOn(":analysis-model:check", ":analysis-core:check", "verifyProductionModules") }
