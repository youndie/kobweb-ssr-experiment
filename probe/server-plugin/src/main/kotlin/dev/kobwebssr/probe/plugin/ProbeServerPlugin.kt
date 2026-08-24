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
     * Which paths get server-rendered, and whether each one may be rendered for a request that
     * carries context the renderer throws away.
     *
     * Hard-coded for now, and that is a known gap rather than a simplification: Kobweb's KSP output
     * already carries the real route table on the JVM as `FrontendData` (research §1.4), and
     * wiring this to it is M5-04.
     */
    private val ssrRoutes = listOf(
        // A constant page: nothing in it reads the request, so no part of the request can change
        // what it should look like. That is a statement by whoever wrote the page, not an inference.
        SsrRoute("/ssr", ignores = RequestContext.entries.toSet()),
        // Not localised, so the language header cannot change it — but it is not declared free of
        // the rest, so a cookie still stops it being served from a render that never saw one.
        SsrRoute("/state", ignores = setOf(RequestContext.LanguagePreference)),
        // Reads its own query string and the visitor's language, and declares neither ignorable.
        // Since M5-02 both of those reach the render, so it is served rather than refused.
        SsrRoute("/echo"),
    )

    private data class SsrRoute(val path: String, val ignores: Set<RequestContext> = emptySet())

    override fun configure(application: Application) {
        application.log.info("[ssr-probe] plugin loaded: ${javaClass.name}")

        application.intercept(ApplicationCallPipeline.Plugins) {
            // The renderer's browser fetches the very page it is rendering from this same server.
            // Without standing aside for it, the plugin would intercept that fetch too and ask the
            // renderer again, forever.
            if (call.request.headers[BYPASS_HEADER] != null) return@intercept

            val path = call.request.path()
            val route = ssrRoutes.firstOrNull { it.path == path }

            if (route != null) {
                // **M5-01 / Risk 9.** Nothing of the visitor's request reaches the renderer: it
                // builds its own URL with only the export flags and sends a single header. So for
                // a request that carries a query string, cookies or credentials, the page that
                // comes back is the page for *some other* request — returned with status 200 and
                // then cached under the path alone, which hands the same wrong page to everyone.
                //
                // Refusing is more expensive than rendering and it is the only honest option
                // available today: a silently wrong page is worse than no server rendering at all.
                // The route-level opt-out is a statement by whoever wrote the page, not a guess.
                val dropped = call.droppedRequestContext() - route.ignores - CARRIED_TO_RENDER
                if (dropped.isNotEmpty()) {
                    application.log.info(
                        "[ssr-probe] not rendering $path: the request carries " +
                            dropped.joinToString { it.description } +
                            ", which the renderer would drop; declare the route as ignoring it if " +
                            "the page genuinely does not read it",
                    )
                    return@intercept
                }

                // Off the Ktor pipeline thread, and this is not hygiene — it is the difference
                // between working and deadlocking. Kobweb runs on Netty, the renderer fetches the
                // page it is rendering *from this same server*, and both the render call and the
                // JDK HTTP client are blocking. Occupy every event-loop thread with waiting
                // interceptors and there is nobody left to serve the renderer's own fetch, so the
                // whole thing waits for its own timeouts. Measured before this line existed: eight
                // concurrent requests all took exactly the client timeout.
                val renderRequest = call.toRenderRequest(path)
                val result = withContext(Dispatchers.IO) {
                    cache.getOrRender(renderRequest.cacheKey) { renderer.render(renderRequest) }
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
            get("/ssr-probe/echo-headers") {
                call.respondText(call.request.headers.names().sorted().joinToString("\n"))
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

/**
 * The kinds of request context that reach the render since **M5-02**, and therefore no longer
 * cause a refusal.
 *
 * Cookies and credentials are deliberately not here. Forwarding them would make every render
 * user-specific, which means either not caching at all — 0.6 s per request per visitor — or
 * caching per user, where one wrong key hands one visitor another's page. Kilua draws the same
 * line and says so: authenticated content is not supported. Refusing is the honest state until
 * there is a design for it.
 */
private val CARRIED_TO_RENDER = setOf(RequestContext.QueryString, RequestContext.LanguagePreference)

/**
 * The kinds of request context a render can throw away.
 *
 * Kinds rather than a single flag, and the reason is not tidiness. Almost every browser sends
 * `Accept-Language`, so a one-bit "this route ignores the request" opt-out would refuse practically
 * every real visitor on any route that had not opted out — that is, it would collapse into an
 * off switch for server rendering, and the pressure would be to declare every route free of
 * everything, which is how a guard stops guarding. Per kind, a page can truthfully say it is not
 * localised without also claiming it ignores its own query string.
 */
private enum class RequestContext(val description: String) {
    QueryString("a query string"),
    Cookies("cookies"),
    Credentials("credentials"),
    LanguagePreference("a language preference"),
}

/**
 * Collects the parts of the request that the renderer knows how to reproduce.
 *
 * Anything the renderer reserves for itself is stripped from the visitor's query string rather
 * than passed through: `_kobwebIsExporting` and `_kobwebColorModeStrategy` change how the page
 * renders, so leaving them under the visitor's control would let a URL decide what the server
 * produces and then have that cached for everyone.
 */
private fun ApplicationCall.toRenderRequest(path: String): RenderRequest {
    val query = request.queryParameters.entries()
        .filterNot { it.key.startsWith("_kobweb") }
        .flatMap { entry -> entry.value.map { entry.key to it } }
        .sortedBy { it.first }
        .joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
    return RenderRequest(
        path = path,
        query = query.ifEmpty { null },
        // Only the first, highest-weighted tag: the browser context takes one locale, and keeping
        // the whole header in the cache key would split the cache by a preference nobody reads.
        locale = request.headers[HttpHeaders.AcceptLanguage]
            ?.substringBefore(',')
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() },
    )
}

/** Names what this particular request carries that would not survive a render. */
private fun ApplicationCall.droppedRequestContext(): Set<RequestContext> = buildSet {
    if (request.queryParameters.entries().isNotEmpty()) add(RequestContext.QueryString)
    if (request.headers[HttpHeaders.Cookie] != null) add(RequestContext.Cookies)
    if (request.headers[HttpHeaders.Authorization] != null) add(RequestContext.Credentials)
    if (request.headers[HttpHeaders.AcceptLanguage] != null) add(RequestContext.LanguagePreference)
}
