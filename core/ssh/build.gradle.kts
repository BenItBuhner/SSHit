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
    api(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    api(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.withType<Test>().configureEach {
    // Integration tests against a live sshd are opt-in; see SshIntegrationTest.
    environment("SSH_TEST_HOST", System.getenv("SSH_TEST_HOST") ?: "")
    environment("SSH_TEST_PORT", System.getenv("SSH_TEST_PORT") ?: "")
    environment("SSH_TEST_USER", System.getenv("SSH_TEST_USER") ?: "")
    environment("SSH_TEST_PASSWORD", System.getenv("SSH_TEST_PASSWORD") ?: "")
    environment("SSH_TEST_KEY_FILE", System.getenv("SSH_TEST_KEY_FILE") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
