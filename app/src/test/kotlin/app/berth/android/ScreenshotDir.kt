package app.berth.android

import java.io.File

/**
 * Where a screenshot test writes its frames: the variant's own folder, `build/outputs/roborazzi/debug`
 * or `build/outputs/roborazzi/release`, named by the build (app/build.gradle.kts), so neither variant's
 * frames overwrite the other's and the two can be compared frame for frame.
 */
val screenshotDir: File = File(
    System.getProperty("user.dir"),
    System.getProperty("berth.roborazziVariant")?.let { "build/outputs/roborazzi/$it" } ?: "build/outputs/roborazzi",
)
