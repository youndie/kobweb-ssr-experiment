package dev.kobwebssr.probe.plugin

import com.varabyte.kobweb.server.plugin.KobwebServerPlugin
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*

/**
 * The M0 probe. One run answers four questions, and the marker in each response body says who
 * produced it, so no answer has to be inferred.
 *
 *  1. Does `ServiceLoader` find a jar placed by the `kobwebServerPlugin` configuration?
 *     `/ssr-probe` responding at all is the answer.
 *  2. Can a plugin serve a route no Kobweb page owns? `/ssr-probe/fresh`.
 *  3. **M0-03.** Can a plugin claim a route a Kobweb page *does* own, using `routing { }`?
 *     `/collide`, against the page of the same name.
 *  4. **M0-04.** Can it claim one from an interceptor that runs before routing?
 *     `/collide2`, against the page of the same name.
 *
 * Note that `configure` is called *after* Kobweb has already run `configureRouting`, so anything
 * registered here through `routing { }` is a later arrival at the same routing tree.
 */
class ProbeServerPlugin : KobwebServerPlugin {
    override fun configure(application: Application) {
        application.log.info("[ssr-probe] plugin loaded: ${javaClass.name}")

        // Question 4: run before Kobweb's routing gets the call at all. `Plugins` is the earliest
        // phase of the call pipeline, and routing is installed on the later `Call` phase.
        application.intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.path() == "/collide2") {
                call.respondText("RENDERED BY THE SERVER PLUGIN: /collide2 (early intercept)")
                finish()
            }
        }

        application.routing {
            get("/ssr-probe") {
                call.respondText("RENDERED BY THE SERVER PLUGIN: /ssr-probe")
            }
            get("/ssr-probe/fresh") {
                call.respondText("RENDERED BY THE SERVER PLUGIN: /ssr-probe/fresh")
            }
            get("/collide") {
                call.respondText("RENDERED BY THE SERVER PLUGIN: /collide (routing)")
            }
        }
    }
}
