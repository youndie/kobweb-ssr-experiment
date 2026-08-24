package dev.kobwebssr.probe.plugin

import com.varabyte.kobweb.server.plugin.KobwebServerPlugin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

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

    private val cache = RenderCache(
        ttl = Duration.ofSeconds(System.getProperty(CACHE_TTL_PROPERTY)?.toLongOrNull() ?: 60),
        enabled = System.getProperty(CACHE_ENABLED_PROPERTY)?.toBooleanStrictOrNull() ?: true,
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
                // Off the Ktor pipeline thread, and this is not hygiene — it is the difference
                // between working and deadlocking. Kobweb runs on Netty, the renderer fetches the
                // page it is rendering *from this same server*, and both the render call and the
                // JDK HTTP client are blocking. Occupy every event-loop thread with waiting
                // interceptors and there is nobody left to serve the renderer's own fetch, so the
                // whole thing waits for its own timeouts. Measured before this line existed: eight
                // concurrent requests all took exactly the client timeout.
                val result = withContext(Dispatchers.IO) {
                    cache.getOrRender(path) { renderer.render(RenderRequest(path)) }
                }
                when (result) {
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
            get("/ssr-probe/cache") {
                call.respondText(cache.stats())
            }
            get("/ssr-probe/cache/clear") {
                val path = call.request.queryParameters["path"]
                call.respondText("invalidated ${cache.invalidate(path)} entr(y/ies)")
            }
        }
    }

    companion object {
        const val RENDERER_URL_PROPERTY = "kobwebssr.renderer.url"
        const val CACHE_ENABLED_PROPERTY = "kobwebssr.cache.enabled"
        const val CACHE_TTL_PROPERTY = "kobwebssr.cache.ttl.seconds"
    }
}

/** Must match the header the renderer sets on its browser context. */
const val BYPASS_HEADER = "X-Kobweb-Ssr-Bypass"
