plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":engine:audio"))
    implementation(project(":engine:dsp"))
    implementation(project(":engine:analysis"))
    implementation(project(":engine:transitions"))
    implementation(project(":engine:metrics"))
    implementation(project(":engine:player"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.muisc.cli.MainKt")
    applicationName = "muisc"
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.test { useJUnitPlatform() }
