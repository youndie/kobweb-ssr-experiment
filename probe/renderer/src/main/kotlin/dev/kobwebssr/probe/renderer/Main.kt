package dev.kobwebssr.probe.renderer

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jsoup.Jsoup
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The browser-backed renderer of M1, run as a sidecar process rather than inside the Kobweb server.
 *
 * It is a separate process for two reasons, and neither is style. The `kobwebServerPlugin`
 * configuration is declared `isTransitive = false`, so a plugin jar arrives at the server alone —
 * bundling Playwright and its driver into it would mean a fat jar of the whole browser tooling.
 * And the seam this milestone is really testing (see D2 in the research) has to survive the
 * renderer being replaced by a Node process or, eventually, by an in-process `renderToString`; a
 * process boundary is the honest version of that seam, not a hidden coupling.
 *
 * Protocol, deliberately the smallest thing that works:
 *
 *     GET /render?path=%2Fsome%2Fpage   ->  200 text/html   the snapshot
 *                                          502 text/plain  the page crashed mid-render
 *     GET /health                       ->  200 text/plain
 */
fun main(args: Array<String>) {
    val target = args.valueOf("--target") ?: "http://localhost:8080"
    val port = args.valueOf("--port")?.toIntOrNull() ?: 7899

    Playwright.create(
        Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")),
    ).use { playwright ->
        playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
            val renderer = SnapshotRenderer(browser, target)
            val server = HttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/health") { it.reply(200, "text/plain", "ok") }
            server.createContext("/render") { exchange ->
                val path = exchange.query("path")
                if (path == null) {
                    exchange.reply(400, "text/plain", "missing 'path'")
                    return@createContext
                }
                try {
                    val startedAt = System.nanoTime()
                    val html = renderer.render(path)
                    val tookMs = (System.nanoTime() - startedAt) / 1_000_000
                    println("[renderer] $path -> ${html.length} chars in ${tookMs}ms")
                    exchange.reply(200, "text/html; charset=utf-8", html)
                } catch (e: Exception) {
                    println("[renderer] $path FAILED: ${e.message}")
                    exchange.reply(502, "text/plain", "render failed: ${e.message}")
                }
            }
            // Single-threaded on purpose. Kilua's SSR serialises rendering behind a spin lock
            // because one composition cannot render two URLs at once; a browser has no such
            // limitation, but pretending otherwise here would hide the queue that M1-05 is
            // supposed to measure. One worker now, a measured pool later.
            server.executor = null
            server.start()
            println("[renderer] listening on http://localhost:$port, rendering against $target")
            Thread.currentThread().join()
        }
    }
}

private class SnapshotRenderer(private val browser: Browser, private val target: String) {
    fun render(path: String): String {
        browser.newContext(
            Browser.NewContextOptions().setExtraHTTPHeaders(mapOf(BYPASS_HEADER to "1")),
        ).use { context ->
            context.newPage().use { page ->
                val errors = mutableListOf<String>()
                page.onPageError { errors += it }
                page.navigate("$target$path?_kobwebIsExporting=true&_kobwebColorModeStrategy=BOTH")
                page.bakeStyleSheets()
                check(errors.isEmpty()) { "page crashed: ${errors.first()}" }
                return Jsoup.parse(page.content()).toString()
            }
        }
    }

    /**
     * Compose HTML creates empty `<style>` nodes and fills them through the CSSOM, so a document
     * saved as-is carries the tags and none of the rules. Writing each sheet's own rules back into
     * its owner node makes the markup self-describing before any JavaScript runs.
     *
     * This is [KobwebExportTask.takeSnapshot]'s script, kept deliberately identical: M1-02 compares
     * this renderer's output against `kobweb export` for the same page, and a "improvement" here
     * would show up as a difference there and cost an afternoon.
     */
    private fun Page.bakeStyleSheets() {
        // language=javascript
        evaluate(
            """
            for (let s = 0; s < document.styleSheets.length; s++) {
                var stylesheet = document.styleSheets[s]
                stylesheet = stylesheet instanceof CSSStyleSheet ? stylesheet : null;

                // Trying to peek at external stylesheets causes a security exception so step over them
                if (stylesheet != null && stylesheet.href == null) {
                    var styleNode = stylesheet.ownerNode
                    styleNode = styleNode instanceof Element ? styleNode : null
                    if (styleNode != null && styleNode.innerHTML == '') {
                        const rules = []
                        for (let r = 0; r < stylesheet.cssRules.length; ++r) {
                            rules.push(stylesheet.cssRules[r].cssText.replace(/(\n)/gm, ''))
                        }
                        styleNode.innerHTML = rules.join('')
                    }
                }
            }
            """.trimIndent(),
        )
    }
}

/**
 * Set by the renderer's browser on every request it makes, and honoured by the server plugin.
 *
 * Without it the arrangement eats itself: the plugin intercepts a page request, asks the renderer
 * for that page, and the renderer's browser asks the same server for the same page — which the
 * plugin intercepts again. A header rather than a query parameter, so the URL the application sees
 * is the URL the visitor asked for.
 */
const val BYPASS_HEADER = "X-Kobweb-Ssr-Bypass"

private fun Array<String>.valueOf(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

private fun HttpExchange.query(name: String): String? = requestURI.rawQuery
    ?.split('&')
    ?.map { it.split('=', limit = 2) }
    ?.firstOrNull { it.size == 2 && it[0] == name }
    ?.let { URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

private fun HttpExchange.reply(code: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
