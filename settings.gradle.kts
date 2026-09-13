// Muisc — Retro-style music player with a DJ transition engine.
//
// The engine modules (engine:*) and the CLI are pure Kotlin/JVM and build anywhere with a JDK.
// The Android app (:app) is only included when an Android SDK is available, so the engine can be
// built and tested on machines without the Android toolchain (CI, desktop experimentation, sandboxes).

import java.util.Properties

val androidSdkAvailable: Boolean = run {
    val localProps = File(rootDir, "local.properties")
    val fromLocal = if (localProps.exists()) {
        Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else null
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val forced = (System.getProperty("muisc.android") ?: System.getenv("MUISC_ANDROID"))
    when (forced?.lowercase()) {
        "true", "1", "on" -> true
        "false", "0", "off" -> false
        else -> listOfNotNull(fromLocal, fromEnv).any { File(it).isDirectory }
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // google() hosts the Android Gradle Plugin. It is added below only when the app module is enabled
        // so that engine-only builds never need to reach dl.google.com.
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "muisc"

include(":engine:audio")
include(":engine:dsp")
include(":engine:analysis")
include(":engine:transitions")
include(":tools:cli")

if (androidSdkAvailable) {
    logger.lifecycle("Muisc: Android SDK found — including :app")
    pluginManagement.repositories.google()
    dependencyResolutionManagement.repositories.google()
    include(":app")
} else {
    logger.lifecycle("Muisc: no Android SDK found — building engine modules only (set sdk.dir in local.properties or ANDROID_HOME to include :app)")
}
