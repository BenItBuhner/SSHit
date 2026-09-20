import com.android.build.api.artifact.SingleArtifact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

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
            // R8 with the optimizing defaults, the app's rules (app/proguard-rules.pro) and the rules the libraries
            // bundle; resources unreachable from the kept code go too. The mapping lands in
            // build/outputs/mapping/release/ with seeds.txt and usage.txt beside it, and verifyReleaseKeepRules
            // (below) reads them after every release build.
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

// verifyReleaseKeepRules: what the release APK itself says about the names the code reaches at runtime. The
// Robolectric suite cannot run over the APK's DEX, so for the app module the proof of the rules in
// proguard-rules.pro is the DEX's own class table (a class kept by name is defined there under it), R8's seeds
// (the members the rules matched, so a constructor reflection calls is in the output), its mapping (what a
// missing name became; that it carries a line table at all, so a frame retraces rather than reading as line 0;
// and that the map id R8 wrote into the DEX as every class's SourceFile is this mapping's pg_map_id, so a report
// from this APK names the mapping that reads it) and the APK's resources (the service file SLF4J reads names the
// binding). Runs after every assembleRelease; the JVM modules' testR8 covers what runs on this machine.
androidComponents {
    // The Android Gradle plugin builds unit tests for the debug variant only. The Robolectric suite runs over the
    // release variant as well (testReleaseUnitTest, part of test): the release manifest and resources, no
    // debug-only dependencies, and the app as the phone gets it short of R8's renaming, which the JVM cannot load.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val verify = tasks.register("verifyReleaseKeepRules") {
            description = "Checks the release APK's DEX, R8's seeds and mapping for the classes the code reaches by name (see app/proguard-rules.pro)."
            group = "verification"
            inputs.file(mapping)
            inputs.dir(apkDir)
            doLast {
                val mappingFile = mapping.get().asFile
                val seedsFile = mappingFile.resolveSibling("seeds.txt")
                val problems = mutableListOf<String>()
                val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
                if (apks.isEmpty()) throw GradleException("verifyReleaseKeepRules: no APK in ${apkDir.get().asFile}")

                // The classes an APK's classes*.dex define, as dotted names, and the source file each names: the
                // class table is the record of what is on the phone under which name. Each class_def_item names
                // its type (each type its descriptor string, "Lorg/bouncycastle/openssl/PEMDecryptor;") and its
                // source_file_idx, a string index or NO_INDEX; the header holds the tables' offsets.
                class DexTable(val classes: Set<String>, val sourceFiles: Set<String>)
                fun dexTable(apk: File): DexTable {
                    val names = HashSet<String>()
                    val sources = HashSet<String>()
                    ZipFile(apk).use { zip ->
                        for (entry in zip.entries().asSequence().filter { Regex("""classes\d*\.dex""").matches(it.name) }) {
                            val dex = ByteBuffer.wrap(zip.getInputStream(entry).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
                            val stringIds = dex.getInt(0x3C)
                            val typeIds = dex.getInt(0x44)
                            val classDefs = dex.getInt(0x60) to dex.getInt(0x64)
                            fun string(index: Int): String {
                                var at = dex.getInt(stringIds + index * 4)
                                while (dex.get(at).toInt() and 0x80 != 0) at++ // uleb128 length in UTF-16 units
                                at++
                                val text = StringBuilder()
                                while (true) { // modified UTF-8, one to three bytes a character, NUL-terminated
                                    val a = dex.get(at++).toInt() and 0xFF
                                    if (a == 0) break
                                    text.append(
                                        when {
                                            a < 0x80 -> a
                                            a and 0xE0 == 0xC0 -> ((a and 0x1F) shl 6) or (dex.get(at++).toInt() and 0x3F)
                                            else -> ((a and 0x0F) shl 12) or ((dex.get(at++).toInt() and 0x3F) shl 6) or (dex.get(at++).toInt() and 0x3F)
                                        }.toChar(),
                                    )
                                }
                                return text.toString()
                            }
                            for (i in 0 until classDefs.first) {
                                val classDef = classDefs.second + i * 32
                                val descriptor = string(dex.getInt(typeIds + dex.getInt(classDef) * 4))
                                names += descriptor.substring(1, descriptor.length - 1).replace('/', '.')
                                val sourceFile = dex.getInt(classDef + 16)
                                if (sourceFile != -1) sources += string(sourceFile)
                            }
                        }
                    }
                    return DexTable(names, sources)
                }

                // mapping.txt: "original -> renamed:" per class, members indented under it. It says what a missing
                // name became; presence is the DEX's to say, since a class kept under its own name with no member
                // left (PEMDecryptor, an interface whose one method nothing calls) has no line here.
                val renamed: Map<String, String> = mappingFile.readLines()
                    .filter { it.endsWith(":") && !it.startsWith(" ") && !it.startsWith("#") && " -> " in it }
                    .associate { line -> line.removeSuffix(":").split(" -> ").let { it[0] to it[1] } }
                // A line table at all (R8's own numbering, "56:58:void <init>(...):88:88", not the source's): without
                // it every frame reads as line 0 and nothing retraces (-keepattributes LineNumberTable).
                val lineNumbers = mappingFile.useLines { lines -> lines.any { it.startsWith("    ") && Regex("""^\s+\d+:\d+:""").containsMatchIn(it) } }
                if (!lineNumbers) problems += "the mapping carries no line table; a crash report's frames would read as line 0 (-keepattributes LineNumberTable)"
                // The map id. With SourceFile kept, R8 writes "r8-map-id-<pg_map_id>" as every class's SourceFile
                // in place of the file name, and the mapping's header carries the same id, so a report's frames
                // (yp3.Q(r8-map-id-75b2...:57)) name the mapping that reads them; checked below against the DEX.
                val mapId = mappingFile.useLines { lines -> lines.take(20).firstOrNull { it.startsWith("# pg_map_id: ") }?.removePrefix("# pg_map_id: ")?.trim() }
                if (mapId.isNullOrEmpty()) problems += "the mapping has no pg_map_id header; a report's frames could not name it"
                renamed.filter { (from, to) -> from.startsWith("org.bouncycastle.jcajce.provider.") && from != to }.keys.take(5)
                    .forEach { problems += "$it was renamed to ${renamed[it]}; BouncyCastleProvider loads it by name" }

                val byName = listOf(
                    "app.berth.android.diagnostics.RingLoggerProvider",
                    "org.bouncycastle.jce.provider.BouncyCastleProvider",
                    "org.bouncycastle.openssl.PEMDecryptor",
                    "app.berth.data.db.BerthDatabase_Impl",
                )
                var providerClasses = 0
                for (apk in apks) {
                    val table = dexTable(apk)
                    val defined = table.classes
                    if (!mapId.isNullOrEmpty() && table.sourceFiles != setOf("r8-map-id-$mapId")) {
                        problems += "${apk.name}'s classes name ${table.sourceFiles.size} source files (${table.sourceFiles.take(3)}) where every one should be this mapping's id, r8-map-id-$mapId; a report's frames would not name the mapping that reads them (-keepattributes SourceFile)"
                    }
                    for (name in byName) {
                        if (name !in defined) {
                            problems += renamed[name]?.let { "$name is ${it} in ${apk.name}, and the code looks it up by name" }
                                ?: "$name is not in ${apk.name} at all (removed, or never compiled in)"
                        }
                    }
                    providerClasses = defined.count { it.startsWith("org.bouncycastle.jcajce.provider.") }
                    if (providerClasses < 100) problems += "only $providerClasses org.bouncycastle.jcajce.provider classes are in ${apk.name} under their names; the provider's registry needs the package"

                    // The SLF4J service file. R8 renames the service interface and names the file after it, as
                    // LoggerFactory loads the renamed class, so the file is found through the mapping; it must name
                    // the binding, which the keep rule holds under its own name.
                    val serviceType = renamed["org.slf4j.spi.SLF4JServiceProvider"] ?: "org.slf4j.spi.SLF4JServiceProvider"
                    ZipFile(apk).use { zip ->
                        val service = zip.getEntry("META-INF/services/$serviceType")
                        val providers = service?.let { zip.getInputStream(it).bufferedReader().readLines() }
                            ?.map { it.substringBefore('#').trim() }?.filter { it.isNotEmpty() }.orEmpty()
                        if ("app.berth.android.diagnostics.RingLoggerProvider" !in providers) {
                            problems += "${apk.name} does not name RingLoggerProvider in META-INF/services/$serviceType (found $providers); sshj's log would go to the NOP logger"
                        }
                    }
                }

                // seeds.txt: every class and member a keep rule matched, constructors as "Class()".
                if (!seedsFile.isFile) {
                    problems += "no seeds.txt beside the mapping (${seedsFile}); R8 did not run with -printseeds"
                } else {
                    val seeds = seedsFile.readLines().toHashSet()
                    for (name in listOf("app.berth.android.diagnostics.RingLoggerProvider", "org.bouncycastle.jce.provider.BouncyCastleProvider", "app.berth.data.db.BerthDatabase_Impl")) {
                        val simple = name.substringAfterLast('.')
                        if ("$name: $simple()" !in seeds) problems += "$name's no-argument constructor is not kept; it is instantiated by name"
                    }
                }

                if (problems.isNotEmpty()) throw GradleException("Release keep rules do not hold:\n" + problems.joinToString("\n") { "  - $it" })
                logger.lifecycle("verifyReleaseKeepRules: ${byName.size} classes defined by name, $providerClasses BouncyCastle provider classes, constructors and the SLF4J service file present in ${apks.map { it.name }}; every class's SourceFile is the mapping's id r8-map-id-$mapId")
            }
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(verify) }
    }
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
