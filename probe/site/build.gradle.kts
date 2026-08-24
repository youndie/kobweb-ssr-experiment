import com.varabyte.kobweb.gradle.application.util.configAsKobwebApplication

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kobweb.application)
}

group = "dev.kobwebssr.probe"
version = "1.0-SNAPSHOT"

kobweb {
    app {
        index {
            description.set("M0 probe: can a third-party server plugin intercept a page route?")
        }
        server {
            // Reachable in the plugin through System.getProperty. The cache switch is here rather
            // than hard-coded because M1-06 has to measure both positions of it: a switch only ever
            // observed in one position says nothing about what it does.
            systemProperties.put(
                "kobwebssr.cache.enabled",
                providers.gradleProperty("ssrCache").orElse("true"),
            )
            // M2-04: the only thing that should have to change to swap renderers. If more than
            // this had to move, the seam was in the wrong place.
            systemProperties.put(
                "kobwebssr.renderer.url",
                providers.gradleProperty("ssrRenderer").orElse("http://localhost:7899"),
            )
        }
    }
}

kotlin {
    configAsKobwebApplication("probe", includeServer = true)

    sourceSets {
        jsMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kobweb.core)
            implementation(libs.kobweb.silk)
        }
        jvmMain.dependencies {
            compileOnly(libs.kobweb.api) // Provided by Kobweb backend at runtime
        }
    }
}

dependencies {
    // Kobweb syncs everything in this configuration into .kobweb/server/plugins via a Sync task,
    // which also deletes anything else living there. Dropping a jar in by hand does not survive.
    //
    // Gated behind a property, and the gate is the point. `kobwebExport` drives a real browser
    // against a running Kobweb server, so a plugin that is loaded during the export gets its
    // responses baked into the static snapshots. Export once with the plugin off to get honest
    // page files, then run with it on to find out who actually answers a request.
    if (project.hasProperty("probePlugin")) {
        add("kobwebServerPlugin", project(":server-plugin"))
    }
}
