package dev.kobwebssr.probe.renderer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

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
 *     GET /render?path=%2Fsome%2Fpage[&query=...][&locale=...]
 *                                       ->  200 text/html   the snapshot
 *                                          502 text/plain  render failed, reason in the body
 *     GET /health                       ->  200 text/plain
 *     GET /stats                        ->  200 text/plain  queue and timing counters
 */
fun main(args: Array<String>) {
    val target = args.valueOf("--target") ?: "http://localhost:8080"
    val port = args.valueOf("--port")?.toIntOrNull() ?: 7899
    val workers = args.valueOf("--workers")?.toIntOrNull() ?: 2
    val instrumentHydration = args.contains("--instrument-hydration")

    val pool = RenderPool(target, workers, instrumentHydration)
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/health") { it.reply(200, "text/plain", "ok") }
    server.createContext("/stats") { it.reply(200, "text/plain", pool.stats()) }
    server.createContext("/render") { exchange ->
        val path = exchange.query("path")
        if (path == null) {
            exchange.reply(400, "text/plain", "missing 'path'")
            return@createContext
        }
        val request = RenderPool.Request(
            path = path,
            query = exchange.query("query"),
            locale = exchange.query("locale"),
        )
        when (val result = pool.render(request)) {
            is RenderOutcome.Ok -> exchange.reply(200, "text/html; charset=utf-8", result.html)
            is RenderOutcome.Failed -> {
                println("[renderer] ${request.describe()} REJECTED: ${result.reason}")
                exchange.reply(502, "text/plain", result.reason)
            }
        }
    }

    // The HTTP front is allowed to accept concurrently; the work is bounded by the pool behind it,
    // which is where the queue that M1-05 measures actually lives. Accepting on one thread instead
    // would move the queue into the kernel's accept backlog, where nothing can report on it.
    server.executor = Executors.newFixedThreadPool(workers * 2)
    server.start()
    println("[renderer] listening on http://localhost:$port, $workers worker(s), rendering against $target")
    Thread.currentThread().join()
}

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
