plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":engine:audio"))
    implementation(project(":engine:dsp"))
    implementation(project(":engine:analysis"))
    implementation(project(":engine:transitions"))
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
