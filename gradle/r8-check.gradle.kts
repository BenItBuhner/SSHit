import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.process.CommandLineArgumentProvider
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

buildscript {
    repositories { mavenCentral() }
    dependencies {
        // ASM, for the test jar's names (below); the version the Android Gradle plugin itself depends on.
        classpath("org.ow2.asm:asm-commons:9.9")
    }
}

/*
 * testR8: a JVM module's own JUnit suite run over R8's output instead of the compiled classes, with the rules the
 * release APK is built with (the Android Gradle plugin's proguard-android-optimize.txt, then app/proguard-rules.pro,
 * then the rules the libraries bundle, in the order minifyReleaseWithR8 applies them), through the R8 inside the
 * plugin (com.android.tools.build:builder, so it is the same R8 build). The plugin's file is the one it writes for
 * the app (:app:extractProguardFiles), not a copy: it carries the keepattributes, -allowaccessmodification and
 * the enum values()/valueOf() rule, which EnumSet and EnumMap reach by reflection, so a proof without it fails
 * on code the APK runs (sshj's EnumSet.of(OpenMode.READ) did, :core:sftp, before the file was in).
 * The program R8 sees is the module's main and test classes and its whole test runtime
 * classpath, so the tests and the code under test go through one shrinking and one renaming; the test runtime
 * (JUnit, kotlin-test) is kept whole by gradle/r8-harness.pro so it can drive the tests, and nothing else is added
 * to the app's rules. A library class the code reaches only by name, and no rule keeps, fails here as it would on
 * a phone: sshj's and BouncyCastle's algorithm lookups in :core:ssh and :core:sftp against the live sshd, the
 * serializers kotlinx.serialization finds at runtime in :core:domain, the emulator in :core:terminal.
 *
 * Applied from a module's build with apply(from = rootProject.file("gradle/r8-check.gradle.kts")); run with
 * ./gradlew testR8. Not part of check: R8 over the SSH stack takes a minute or two per module.
 */

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val testSet = extensions.getByType<SourceSetContainer>().getByName("test")

val r8Tool = configurations.create("r8Tool") {
    isCanBeConsumed = false
    description = "The R8 the Android Gradle plugin runs, for testR8."
}
dependencies {
    r8Tool("com.android.tools.build:builder:${libs.findVersion("agp").get().requiredVersion}")
}

val r8Dir = layout.buildDirectory.dir("r8")

/**
 * R8 refuses a name DEX cannot hold, whatever the output format, and Kotlin tests are named in backticks with
 * spaces and punctuation, which reach their lambdas' class names and EnclosingMethod attributes too. The tests
 * are found by their annotation and never called by name, so the test classes go in with every such name
 * rewritten to DEX's alphabet, consistently across the jar (`a burst of transfers` -> `a_burst_of_transfers`).
 */
val r8TestJar = tasks.register("r8TestJar") {
    description = "The module's test classes as a jar for R8, with names DEX allows."
    val output: FileCollection = files(testSet.output)
    val out = r8Dir.map { it.file("tests.jar") }
    inputs.files(output)
    outputs.file(out)
    doLast {
        // DEX's SimpleName before version 040: letters, digits, '$', '-' and '_'. The hyphen matters: Kotlin's
        // value-class members (Result.constructor-impl) are referenced from the tests and declared in the stdlib,
        // so their names must be left exactly as they are. <init> and <clinit> are the JVM's, not names.
        fun dexSafe(name: String): String =
            if (name.startsWith("<")) name
            else name.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '$' || it == '-') it else '_' }.joinToString("")

        val remapper = object : Remapper() {
            override fun map(internalName: String): String = internalName.split('/').joinToString("/") { dexSafe(it) }
            override fun mapMethodName(owner: String, name: String, descriptor: String): String = dexSafe(name)
        }

        JarOutputStream(out.get().asFile.outputStream().buffered()).use { jar ->
            val seen = HashSet<String>()
            for (dir in output.files.filter { it.isDirectory }) {
                for (file in dir.walkTopDown().filter { it.isFile }) {
                    val path = file.relativeTo(dir).invariantSeparatorsPath
                    if (!path.endsWith(".class")) {
                        if (!seen.add(path)) continue
                        jar.putNextEntry(JarEntry(path))
                        file.inputStream().use { it.copyTo(jar) }
                        jar.closeEntry()
                        continue
                    }
                    val reader = ClassReader(file.readBytes())
                    val writer = ClassWriter(0)
                    reader.accept(ClassRemapper(writer, remapper), 0)
                    val entry = remapper.map(reader.className) + ".class"
                    if (!seen.add(entry)) continue
                    jar.putNextEntry(JarEntry(entry))
                    jar.write(writer.toByteArray())
                    jar.closeEntry()
                }
            }
        }
    }
}

/** Everything R8 processes: the module's classes, its tests, and all their test runtime classpath. */
val programJars: FileCollection = files(tasks.named("jar"), r8TestJar, configurations.getByName("testRuntimeClasspath"))

val r8BundledRules = tasks.register("r8BundledRules") {
    description = "The keep rules the program's jars carry in META-INF, as the Android Gradle plugin reads them (r8 rules first, else proguard rules)."
    val jars = programJars
    val out = r8Dir.map { it.dir("bundled-rules") }
    inputs.files(jars)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        for (jar in jars.files.filter { it.name.endsWith(".jar") }) {
            ZipFile(jar).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".pro") }.toList()
                val chosen = entries.filter { it.name.startsWith("META-INF/com.android.tools/r8/") }
                    .ifEmpty { entries.filter { it.name.startsWith("META-INF/proguard/") } }
                for (entry in chosen) {
                    val target = dir.resolve(jar.nameWithoutExtension + "-" + entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}

/**
 * The Android Gradle plugin's default rules as the app's release build gets them: getDefaultProguardFile writes
 * proguard-android-optimize.txt-<plugin version> under the app's intermediates from :app:extractProguardFiles,
 * and app/build.gradle.kts names it first in the release proguardFiles.
 */
val defaultRules = rootProject.layout.projectDirectory
    .file("app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-${libs.findVersion("agp").get().requiredVersion}")
    .asFile

val r8Classes = tasks.register<JavaExec>("r8Classes") {
    description = "R8 over the module, its tests and their runtime, with the release APK's rules; classfile output."
    group = "verification"
    dependsOn(":app:extractProguardFiles")
    classpath = r8Tool
    mainClass.set("com.android.tools.r8.R8")
    jvmArgs("-Xmx2g")
    val jars = programJars
    val bundled = r8BundledRules.map { it.outputs.files.singleFile }
    val pluginRules = defaultRules
    val appRules = rootProject.layout.projectDirectory.file("app/proguard-rules.pro").asFile
    val harnessRules = rootProject.layout.projectDirectory.file("gradle/r8-harness.pro").asFile
    val out = r8Dir.map { it.file("classes.jar") }
    val mapping = r8Dir.map { it.file("mapping.txt") }
    val configuration = r8Dir.map { it.file("configuration.txt") }
    val javaHome = providers.systemProperty("java.home").get()
    inputs.files(jars, pluginRules, appRules, harnessRules)
    inputs.dir(bundled)
    outputs.files(out, mapping, configuration)
    doFirst {
        check(pluginRules.isFile) { "r8Classes: the plugin's default rules are not at $pluginRules; :app:extractProguardFiles writes them there" }
        check("-keepclassmembers enum *" in pluginRules.readText()) { "r8Classes: $pluginRules is not the plugin's proguard-android-optimize.txt (no enum rule in it)" }
    }
    argumentProviders += CommandLineArgumentProvider {
        val bundledRules = bundled.get().listFiles().orEmpty().filter { it.name.endsWith(".pro") }.sorted()
        // No data resources: BouncyCastle's jar is signed, and its signature files over the shrunk classes would
        // stop the JVM loading them; the services the tests need come through r8Resources instead.
        listOf("--classfile", "--release", "--no-data-resources", "--lib", javaHome) +
            listOf("--pg-conf", pluginRules.absolutePath, "--pg-conf", appRules.absolutePath, "--pg-conf", harnessRules.absolutePath) +
            bundledRules.flatMap { listOf("--pg-conf", it.absolutePath) } +
            listOf("--pg-map-output", mapping.get().asFile.absolutePath, "--pg-conf-output", configuration.get().asFile.absolutePath) +
            listOf("--output", out.get().asFile.absolutePath) +
            jars.files.filter { it.name.endsWith(".jar") }.map { it.absolutePath }
    }
}

val r8Resources = tasks.register("r8Resources") {
    // With --no-data-resources R8 never sees the service files, so they are carried across as they are. (The app
    // build hands R8 the merged resources, and R8 writes them back named after the service interfaces' new names,
    // which is what the renamed LoggerFactory loads; verifyReleaseKeepRules follows the mapping to that file.)
    description = "META-INF/services from the program's jars, which R8 does not see here, so ServiceLoader finds what it would in the APK."
    val jars = programJars
    val out = r8Dir.map { it.dir("resources") }
    inputs.files(jars)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        for (jar in jars.files.filter { it.name.endsWith(".jar") }) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }) {
                    val target = dir.resolve(entry.name)
                    target.parentFile.mkdirs()
                    // Several jars may declare providers of one service: their lines are joined, as the APK's merger does.
                    zip.getInputStream(entry).use { input -> target.appendText(input.reader().readText().let { if (it.endsWith("\n")) it else it + "\n" }) }
                }
            }
        }
    }
}

tasks.register<Test>("testR8") {
    description = "Runs the module's tests over R8's output with the app's release rules (see gradle/r8-check.gradle.kts)."
    group = "verification"
    testClassesDirs = testSet.output.classesDirs
    classpath = files(r8Classes.map { it.outputs.files.filter { f -> f.name == "classes.jar" } }, r8Resources.map { it.outputs.files })
    useJUnit()
}
