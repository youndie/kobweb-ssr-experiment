plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.kobwebssr.probe"
version = "1.0-SNAPSHOT"

dependencies {
    // Provided by the Kobweb server at runtime, so compileOnly. The artifact is
    // `kobweb-server-plugin`, not `server-plugin` — see M0 notes.
    compileOnly(libs.kobweb.server.plugin)
    compileOnly(libs.ktor.server.core)
}

kotlin {
    jvmToolchain(21)
}
