plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Compile with the local JDK 21 but emit Java 17 bytecode so the Android app (D8) can consume these modules.
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // javax.sound SPI providers so the JVM decoder can read MP3 / FLAC / OGG-Vorbis in addition to WAV/AIFF.
    // These are JVM-only; the Android app uses MediaCodec via its own AudioDecoder implementation.
    implementation(libs.mp3spi)
    implementation(libs.jflac)
    implementation(libs.vorbisspi)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
