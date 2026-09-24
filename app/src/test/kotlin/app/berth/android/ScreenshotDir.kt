package app.berth.android

import java.io.File

/**
 * Where a screenshot test writes its frames: the variant's own folder, `build/outputs/roborazzi/debug`
 * or `build/outputs/roborazzi/release`, named by the build (app/build.gradle.kts). Debug and Release
 * draw a detached tab's frame a level apart, so one folder for both kept whichever variant ran second.
 */
val screenshotDir: File = File(
    System.getProperty("user.dir"),
    System.getProperty("berth.roborazziVariant")?.let { "build/outputs/roborazzi/$it" } ?: "build/outputs/roborazzi",
)
