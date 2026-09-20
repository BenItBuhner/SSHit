# Berth's release rules for R8. Every rule here is for a class the code reaches by name at runtime; the frameworks'
# own rules (Compose, Hilt, Room, kotlinx.serialization, coroutines) come bundled with their libraries and are
# applied by the build. Two checks keep this file honest: gradle/r8-check.gradle.kts runs each JVM module's test
# suite over R8's output with these rules (./gradlew testR8), against the live sshd for :core:ssh and :core:sftp,
# and app/build.gradle.kts reads the release APK's DEX, and R8's seeds and mapping, for the names below
# (verifyReleaseKeepRules).

# Crash reports are written on the phone and read elsewhere, with the release's mapping at hand. R8 renames the
# classes and methods, renumbers the line table, and writes the release's map id into every class's SourceFile in
# place of the file name, so a release frame reads yp3.Q(r8-map-id-75b2...:57), not TerminalSession.kt:212; the
# two attributes are kept so that frame retraces at all (retrace mapping.txt report.txt), and the id in it names
# which mapping.txt reads it (the mapping's own pg_map_id header; the report's head repeats it once, and
# verifyReleaseKeepRules checks the APK and the mapping agree on it). The mapping written to
# app/build/outputs/mapping/release/mapping.txt is attached to the GitHub release the tag builds
# (.github/workflows/release.yml), for as long as the release exists; for a pull request it is CI's 14-day artifact.
-keepattributes SourceFile,LineNumberTable

# SLF4J finds the app's logger binding (the diagnostics log ring) through META-INF/services, by name, and
# instantiates it with the no-argument constructor.
-keep class app.berth.android.diagnostics.RingLoggerProvider { <init>(); }

# BouncyCastle's JCA provider is a registry of class names. When the provider is created it loads each
# <Algorithm>$Mappings class in this package by name, and each of those registers its engines (ciphers, digests,
# MACs, key factories, signatures, key agreements, key generators) by name, to be instantiated by
# Cipher.getInstance and the rest later. Every class in the package therefore stays under its own name with its
# no-argument constructor; the members are reached from there, and the crypto, ASN.1 and math classes under them
# are kept by reference like any other code. The provider class itself is constructed by SshSecurity, which also
# turns sshj's own registration off, but sshj's SecurityUtils looks it up by this name when that is on, so the
# name stays too.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { <init>(); }
-keep class org.bouncycastle.jcajce.provider.** { <init>(); }

# sshj checks for BouncyCastle's PEM decryption by this name before it decrypts an encrypted PKCS#8 key
# (PKCS8KeyFile); without the class it would read the key as unprotected and fail.
-keep class org.bouncycastle.openssl.PEMDecryptor

# BouncyCastle refers to JNDI and the JDK's XML and AWT classes, which Android has no use for and no code path
# reaches; sshj to the SLF4J bindings it does not find. Warnings about those missing classes are not errors.
-dontwarn javax.naming.**
-dontwarn org.slf4j.impl.**
