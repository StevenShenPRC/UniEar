plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val upstream = rootProject.file("upstream/HyperEars")
val expected = rootProject.file("upstream/hyperears.rev").readText().trim()
check(upstream.resolve(".git").exists()) {
    "HyperEars checkout is missing. Run python scripts/hyperears.py checkout."
}
val revision = providers.exec {
    commandLine("git", "-C", upstream.absolutePath, "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()
val required = providers.gradleProperty("hyperearsRevision").orElse(expected).get()
check(revision == required) {
    "HyperEars checkout does not match ${required}. Run scripts/hyperears.py checkout."
}
check(upstream.resolve("integration/src/main/java").isDirectory &&
    upstream.resolve("protocol/src/main/java").isDirectory) {
    "HyperEars protocol source directories are missing or have moved."
}

kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDir(upstream.resolve("protocol/src/main/java"))
        kotlin.srcDir(upstream.resolve("integration/src/main/java"))
    }
    sourceSets.named("test") {
        kotlin.srcDir(upstream.resolve("protocol/src/test/java"))
        kotlin.srcDir(upstream.resolve("integration/src/test/java"))
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.processResources {
    into("META-INF/hyperears") {
        from(upstream.resolve("LICENSE"), upstream.resolve("THIRD_PARTY_NOTICES.md"))
    }
}
