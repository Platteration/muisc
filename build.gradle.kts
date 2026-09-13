// Root build file. Module-specific configuration lives in each module's build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "dev.muisc"
    version = "0.1.0"
}
