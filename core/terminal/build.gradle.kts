import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    // The throughput benchmark is opt-in; see TerminalThroughputBenchmark.
    environment("BERTH_BENCH", System.getenv("BERTH_BENCH") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// testR8: this module's tests over R8's output with the app's release rules; see gradle/r8-check.gradle.kts.
apply(from = rootProject.file("gradle/r8-check.gradle.kts"))
