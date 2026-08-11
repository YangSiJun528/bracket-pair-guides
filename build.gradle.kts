plugins {
    base
    id("org.jetbrains.kotlin.jvm") apply false
}

tasks.named("check") {
    dependsOn(":plugin:check", ":benchmarks:check")
}
