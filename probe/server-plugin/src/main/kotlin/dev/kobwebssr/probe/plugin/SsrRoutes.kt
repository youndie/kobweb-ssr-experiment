package dev.kobwebssr.probe.plugin

import java.io.File

/**
 * **M5-04.** Which paths get server-rendered, discovered from what the build already knows instead
 * of from a list typed into this file.
 *
 * Kobweb's KSP processor writes every `@Page` route into `frontend.json`, and the Gradle plugin
 * reads it to generate the client's entry point. The same file is on disk next to the site's
 * resources at runtime, so nothing new has to be produced for the server to know the route table.
 *
 * **What discovery cannot give you.** The metadata lists paths. It says nothing about whether a
 * page reads its query string, so [exemptions] stays hand-written — and it has to, because it is a
 * statement by whoever wrote the page rather than a fact about it. Replacing one hand-maintained
 * list did not remove the other, and pretending otherwise would mean guessing on the visitor's
 * behalf about which requests may be answered from a render that never saw them.
 *
 * **What is not solved.** `frontend.json` is a build output. A deployed site ships `.kobweb/site`,
 * not `build/`, so in production this file may simply be absent — [discover] then falls back to
 * the declared routes and says so. Putting the metadata where a deployed server can read it is
 * work for the Gradle plugin, which is the same place §1.4 of the research already pointed at.
 */
object SsrRoutes {
    private const val METADATA = "build/processedResources/js/main/kobweb/metadata/frontend.json"

    /**
     * Read with a regular expression rather than a JSON parser, and that is a deliberate limit
     * rather than laziness: the `kobwebServerPlugin` configuration is not transitive, so anything
     * this plugin needs at runtime must already be on the Kobweb server's classpath. Depending on
     * a serialisation library being there is a bet; matching one field is not.
     */
    private val ROUTE = Regex("\"route\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * The M0 fixtures, kept out of server rendering on purpose.
     *
     * `/collide` and `/collide2` exist to show where a plugin can and cannot take over a route.
     * Server-rendering them would make the interceptor answer first and erase exactly the evidence
     * they were built to provide.
     */
    private val fixtures = setOf("/collide", "/collide2")

    /**
     * **M5-05.** Routes Kobweb would answer with a redirect, read out of `conf.yaml`.
     *
     * Kobweb handles `server.redirects` itself and answers a real 301 with a `Location` header —
     * which was worth checking, since this milestone began from the assumption that redirects were
     * client-side only. The defect is narrower than that and worse: when a redirect's `from` is
     * also a `@Page` route, discovery puts it in the SSR set and the interceptor answers first. The
     * visitor then gets **the target's content under the source URL with status 200** — wrong body,
     * wrong status, no `Location` — because the renderer's own fetch follows the redirect.
     *
     * Kobweb treats `from` as a regular expression, so this does too. A pattern that fails to
     * compile is skipped rather than fatal: refusing to start the server over a redirect rule is a
     * worse outcome than not server-rendering one route.
     */
    private fun redirectSources(siteRoot: File): List<Regex> {
        val conf = File(siteRoot, ".kobweb/conf.yaml")
        if (!conf.isFile) return emptyList()
        return Regex("""\bfrom:\s*"?([^"\s]+)"?""").findAll(conf.readText())
            .mapNotNull { runCatching { Regex(it.groupValues[1]) }.getOrNull() }
            .toList()
    }

    /** Per-route statements about what the page does not read. See the class comment. */
    private val exemptions = mapOf(
        "/ssr" to RequestContext.entries.toSet(),
        "/state" to setOf(RequestContext.LanguagePreference),
    )

    private val declared = listOf("/ssr", "/state", "/echo")

    fun discover(siteRoot: File = File(".")): Discovery {
        val file = File(siteRoot, METADATA)
        if (!file.isFile) {
            return Discovery(
                routes = declared.map { SsrRoute(it, exemptions[it].orEmpty()) },
                source = "the declared list — $METADATA is not on disk",
            )
        }
        val redirected = redirectSources(siteRoot)
        val all = ROUTE.findAll(file.readText()).map { it.groupValues[1] }.distinct().sorted().toList()
        val found = all
            .filterNot { it in fixtures }
            .filterNot { route -> redirected.any { it.matches(route) } }
        val heldBack = all.size - found.size
        return Discovery(
            routes = found.map { SsrRoute(it, exemptions[it].orEmpty()) },
            source = "$METADATA (${found.size} routes, $heldBack held back as fixtures or redirects)",
        )
    }

    data class Discovery(val routes: List<SsrRoute>, val source: String)
}

/**
 * A page the plugin will serve from a render, and what its author says it does not read.
 *
 * @param ignores kinds of request context that cannot change this page's output. Empty means the
 *   page is only rendered for requests carrying nothing the renderer would drop.
 */
data class SsrRoute(val path: String, val ignores: Set<RequestContext> = emptySet())
