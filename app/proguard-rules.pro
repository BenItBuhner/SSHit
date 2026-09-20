# SLF4J finds the app's logger binding (the diagnostics log ring) through META-INF/services, by name.
-keep class app.berth.android.diagnostics.RingLoggerProvider { *; }

# sshj and BouncyCastle use reflection for algorithm lookup.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
