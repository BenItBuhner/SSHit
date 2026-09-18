# sshj and BouncyCastle use reflection for algorithm lookup.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
