# Rules for testR8 (gradle/r8-check.gradle.kts) only: what the test runtime needs to drive a module's tests over
# R8's output. Nothing here touches the libraries under proof; the app's rules are app/proguard-rules.pro.

# JUnit finds the tests through reflection: the annotations, a class with @Test methods and its no-argument
# constructor, and the lifecycle members of any class kept.
-keepattributes *Annotation*
-keepclasseswithmembers class * {
    <init>();
    @org.junit.Test <methods>;
}
-keepclassmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.BeforeClass <methods>;
    @org.junit.AfterClass <methods>;
    @org.junit.Rule <fields>;
    @org.junit.Rule <methods>;
    @org.junit.ClassRule <fields>;
    @org.junit.ClassRule <methods>;
}
-keep @org.junit.runner.RunWith class * { *; }

# Gradle's test worker drives JUnit, kotlin-test finds its asserter through ServiceLoader, and coroutines-test
# its main dispatcher: kept whole. None of them are in the APK.
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class kotlin.test.** { *; }
-keep class kotlinx.coroutines.test.** { *; }
-dontwarn org.junit.**
-dontwarn junit.**
-dontwarn org.hamcrest.**
