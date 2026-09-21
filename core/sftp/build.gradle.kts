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
    api(project(":core:ssh"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.withType<Test>().configureEach {
    // Integration tests against a live sshd with an sftp subsystem are opt-in; see SftpIntegrationTest.
    // Each variable is an input of the task as well as its environment, so a cached run made without
    // them (every live test skipped) is never restored for a run made with them; the password's
    // presence is the input, not its value.
    for (name in listOf("SSH_TEST_HOST", "SSH_TEST_PORT", "SSH_TEST_USER", "SSH_TEST_PASSWORD")) {
        val value = System.getenv(name) ?: ""
        environment(name, value)
        inputs.property("env.$name", if (name == "SSH_TEST_PASSWORD") value.isNotEmpty().toString() else value)
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// testR8: this module's tests over R8's output with the app's release rules; see gradle/r8-check.gradle.kts.
apply(from = rootProject.file("gradle/r8-check.gradle.kts"))
