// Pure Kotlin/JVM. Deliberately has NO Android dependency, so the action schema
// and state machine are unit-testable on the JVM with no device or emulator —
// which matters on a machine that can't comfortably run one.
// No version here on purpose: AGP 9's built-in Kotlin support already puts the Kotlin
// Gradle Plugin on the build classpath, and requesting a version again fails with
// "already on the classpath with an unknown version".
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Kotlin otherwise defaults to the JDK running Gradle (21 here), which Gradle rejects as
// inconsistent with the Java tasks above. Pinning the target keeps this module's bytecode
// at the same level as the Android modules that consume it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // The compression benchmark prints its measured reduction; without this the numbers
    // that end up on the resume would be invisible.
    testLogging {
        showStandardStreams = true
    }
}
