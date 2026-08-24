plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "dev.kobwebssr.probe"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(libs.playwright)
    implementation(libs.jsoup)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.kobwebssr.probe.renderer.MainKt")
}
