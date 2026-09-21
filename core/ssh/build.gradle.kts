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
    testImplementation(project(":core:terminal"))
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.withType<Test>().configureEach {
    // Integration tests against a live sshd are opt-in; see SshIntegrationTest. SSH_TEST_JUMP_PORT is a
    // second sshd (same user and password, its own host keys) for the jump chain tests; BERTH_DEMO_OUT
    // is where the headless terminal demo writes PNG frames when set (see TerminalDemoHarness). Each
    // variable is an input of the task as well as its environment, so a cached run made without them
    // (every live test skipped) is never restored for a run made with them; the password's presence is
    // the input, not its value.
    for (name in listOf("SSH_TEST_HOST", "SSH_TEST_PORT", "SSH_TEST_USER", "SSH_TEST_PASSWORD", "SSH_TEST_KEY_FILE", "SSH_TEST_JUMP_PORT", "BERTH_DEMO_OUT", "BERTH_DEMO_FONT")) {
        val value = System.getenv(name) ?: ""
        environment(name, value)
        inputs.property("env.$name", if (name == "SSH_TEST_PASSWORD") value.isNotEmpty().toString() else value)
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// testR8: this module's tests over R8's output with the app's release rules; see gradle/r8-check.gradle.kts.
apply(from = rootProject.file("gradle/r8-check.gradle.kts"))
