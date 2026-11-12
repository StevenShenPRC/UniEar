plugins {
    // Load AGP and the Kotlin plugins together so Android and JVM share the compiler.
    id("com.android.application") apply false
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
}
