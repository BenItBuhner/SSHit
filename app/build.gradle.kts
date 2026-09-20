plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

val appName: String = providers.gradleProperty("app.name").get()
val appApplicationId: String = providers.gradleProperty("app.applicationId").get()

// The version is written once, in gradle.properties: app.versionName is MAJOR.MINOR.PATCH and app.versionBuild
// counts re-releases of that name (0 to 99, normally 0). versionCode follows from the two, so it can never be
// forgotten or go backwards while the name goes forwards: MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 +
// BUILD, so 0.1.0 is 10000, 0.1.1 is 10100, 1.0.0 is 1000000, and the second upload of 1.0.0 is 1000001.
val appVersionName: String = providers.gradleProperty("app.versionName").get()
val appVersionBuild: Int = providers.gradleProperty("app.versionBuild").map { it.toInt() }.getOrElse(0)
val appVersionCode: Int = run {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersionName)
        ?: error("app.versionName must be MAJOR.MINOR.PATCH, not '$appVersionName'")
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    require(major in 0..2000 && minor in 0..99 && patch in 0..99 && appVersionBuild in 0..99) {
        "app.versionName $appVersionName / app.versionBuild $appVersionBuild are outside the version scheme (see app/build.gradle.kts)"
    }
    val code = major * 1_000_000 + minor * 10_000 + patch * 100 + appVersionBuild
    require(code >= 1) { "versionCode must be at least 1; 0.0.0 with build 0 is not a version" }
    code
}

// The release key is never in the repository. Four values, each read from a Gradle property (in
// ~/.gradle/gradle.properties or with -P; the property wins) or else from the environment:
//   berth.release.storeFile      BERTH_RELEASE_STORE_FILE      path to the keystore
//   berth.release.storePassword  BERTH_RELEASE_STORE_PASSWORD
//   berth.release.keyAlias       BERTH_RELEASE_KEY_ALIAS
//   berth.release.keyPassword    BERTH_RELEASE_KEY_PASSWORD
// With none of them set a release build is unsigned (app-release-unsigned.apk), which is what CI builds to prove
// R8 and to measure; with some set and some not, the build stops rather than sign with a guess. The debug key
// in app/keystore/debug.keystore is a different matter: not a secret, and pinned for every machine.
fun releaseSetting(property: String, variable: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orNull?.takeIf { it.isNotBlank() }
val releaseSigning: Map<String, String?> = mapOf(
    "storeFile" to releaseSetting("berth.release.storeFile", "BERTH_RELEASE_STORE_FILE"),
    "storePassword" to releaseSetting("berth.release.storePassword", "BERTH_RELEASE_STORE_PASSWORD"),
    "keyAlias" to releaseSetting("berth.release.keyAlias", "BERTH_RELEASE_KEY_ALIAS"),
    "keyPassword" to releaseSetting("berth.release.keyPassword", "BERTH_RELEASE_KEY_PASSWORD"),
)
val releaseSigningConfigured: Boolean = when (releaseSigning.values.count { it != null }) {
    0 -> false
    releaseSigning.size -> true
    else -> error(
        "Release signing needs all four of berth.release.storeFile, storePassword, keyAlias and keyPassword " +
            "(or their BERTH_RELEASE_* variables); set: ${releaseSigning.filterValues { it != null }.keys}",
    )
}

android {
    namespace = "app.berth.android"
    compileSdk = 37

    defaultConfig {
        applicationId = appApplicationId
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        resValue("string", "app_name", appName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // One debug key for every machine, so a debug build from any of them installs over the one already on a
        // phone instead of failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Same credentials as an auto-generated
        // Android debug keystore; it is a debug key, not a secret.
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile")!!).also {
                    require(it.isFile) { "berth.release.storeFile / BERTH_RELEASE_STORE_FILE points at nothing: $it" }
                }
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            resValue("string", "app_name", "$appName (debug)")
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:terminal"))
    implementation(project(":core:ssh"))
    implementation(project(":core:sftp"))
    implementation(project(":core:data"))

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // sshj logs through SLF4J; the app binds it to its own log ring (RingLoggerProvider), so a report carries the transport's account.
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
}

tasks.withType<Test>().configureEach {
    // Screenshot tests that drive a live session read the same sshd variables as :core:ssh, plus the
    // P-256 key the Keystore stand-in signs with (its public half in the test user's authorized_keys)
    // and the second sshd a jump chain goes through (SSH_TEST_JUMP_PORT).
    for (name in listOf("SSH_TEST_HOST", "SSH_TEST_PORT", "SSH_TEST_USER", "SSH_TEST_PASSWORD", "SSH_TEST_P256_KEY_FILE", "SSH_TEST_JUMP_PORT")) {
        environment(name, System.getenv(name) ?: "")
    }
    maxHeapSize = "3g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
