package dev.kobwebssr.probe.plugin

import com.varabyte.kobweb.server.plugin.KobwebServerPlugin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*

/**
 * Two milestones live in one plugin, kept apart on purpose.
 *
 * **M0** established where a third-party plugin can and cannot take over a route. Its evidence is
 * the `/ssr-probe`, `/collide` and `/collide2` handlers below, and they are left exactly as they
 * were measured so `probe/README.md`'s table stays reproducible.
 *
 * **M1** is the part that renders: [ssrRoutes] are answered from [PageRenderer] instead of from
 * Kobweb's own handling, through the interceptor M0 proved is the only hook that wins in both
 * layouts.
 */
class ProbeServerPlugin : KobwebServerPlugin {
    private val renderer: PageRenderer = HttpSidecarRenderer(
        baseUrl = System.getProperty(RENDERER_URL_PROPERTY) ?: "http://localhost:7899",
    )

    /**
     * Which paths get server-rendered. Hard-coded for M1, and that is a known gap rather than a
     * simplification: Kobweb's KSP output already carries the real route table on the JVM as
     * `FrontendData` (see research §1.4), and wiring this to it belongs with the Gradle plugin
     * work, not here.
     */
    private val ssrRoutes = setOf("/ssr")

    override fun configure(application: Application) {
        application.log.info("[ssr-probe] plugin loaded: ${javaClass.name}")

        application.intercept(ApplicationCallPipeline.Plugins) {
            // The renderer's browser fetches the very page it is rendering from this same server.
            // Without standing aside for it, the plugin would intercept that fetch too and ask the
            // renderer again, forever.
            if (call.request.headers[BYPASS_HEADER] != null) return@intercept

            val path = call.request.path()

            if (path in ssrRoutes) {
                when (val result = renderer.render(RenderRequest(path))) {
                    is RenderResult.Rendered -> {
                        call.respondText(result.html, ContentType.Text.Html)
                        finish()
                    }
                    // Standing aside rather than returning 5xx: an unavailable renderer should cost
                    // the visitor a client-rendered page, not an error page. The log line is what
                    // makes the difference visible, since a silently degraded SSR looks exactly
                    // like a working one from outside.
                    is RenderResult.Failed -> {
                        application.log.warn("[ssr-probe] falling back to Kobweb for $path: ${result.reason}")
                    }
                }
                return@intercept
            }

            // M0-04's evidence: claiming a real page's route from before routing.
            if (path == "/collide2") {
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
            // M0-03's evidence: claiming a real page's route from routing { }, which loses in the
            // static layout and wins in the fullstack one.
            get("/collide") {
                call.respondText("RENDERED BY THE SERVER PLUGIN: /collide (routing)")
            }
            get("/ssr-probe/renderer-health") {
                call.respondText(if (renderer.isHealthy()) "renderer: up" else "renderer: down")
            }
        }
    }

    companion object {
        const val RENDERER_URL_PROPERTY = "kobwebssr.renderer.url"
    }
}

/** Must match the header the renderer sets on its browser context. */
const val BYPASS_HEADER = "X-Kobweb-Ssr-Bypass"
