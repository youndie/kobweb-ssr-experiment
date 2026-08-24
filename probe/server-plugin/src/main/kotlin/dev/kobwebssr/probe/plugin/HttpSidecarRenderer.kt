package dev.kobwebssr.probe.plugin

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

/**
 * Fragments that appear in the names of Netty's and Ktor's own threads. Matching by name is coarse
 * and it is what is available: the plugin has no compile-time dependency on the server's internals,
 * and a coarse check that fires is worth more than a precise one that needs one.
 */
private val EVENT_LOOP_THREADS = listOf("eventLoop", "nioEventLoop", "ktor-server")

/**
 * Talks to the renderer sidecar over HTTP.
 *
 * Uses the JDK's own client rather than Ktor's on purpose. The `kobwebServerPlugin` configuration
 * is declared `isTransitive = false`, so whatever this plugin needs at runtime has to already be
 * on the Kobweb server's classpath or be inside this jar. `java.net.http` is neither risk.
 */
class HttpSidecarRenderer(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(20),
) : PageRenderer {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    override fun render(request: RenderRequest): RenderResult {
        refuseToBlockTheEventLoop()
        val query = buildString {
            append("path=").append(request.path.urlEncoded())
            request.query?.takeIf { it.isNotEmpty() }?.let { append("&query=").append(it.urlEncoded()) }
            request.locale?.let { append("&locale=").append(it.urlEncoded()) }
        }
        return try {
            val response = client.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/render?$query"))
                    .timeout(timeout)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() == 200) {
                RenderResult.Rendered(response.body())
            } else {
                RenderResult.Failed("renderer answered ${response.statusCode()}: ${response.body().take(200)}")
            }
        } catch (e: Exception) {
            RenderResult.Failed("renderer unreachable at $baseUrl: ${e.message}")
        }
    }

    /**
     * **M5-08.** Refuses to run on a thread the server needs in order to answer.
     *
     * This call blocks, and the renderer it is waiting for fetches the page it is rendering from
     * the very server whose thread this is. Occupy the event loop with waiting and there is nobody
     * left to serve that fetch: measured before this was understood, eight concurrent requests all
     * took exactly the client timeout, and from outside it read as "slow under load" rather than as
     * a deadlock (research §1.8).
     *
     * The rule lived in a comment until now, which is to say it lived nowhere the day someone moved
     * this call. Throwing turns a regression into a loud, safe failure: the caller treats it as a
     * failed render, falls back to Kobweb, and logs the reason. Server rendering stops working
     * visibly instead of hanging quietly.
     */
    private fun refuseToBlockTheEventLoop() {
        val thread = Thread.currentThread().name
        check(EVENT_LOOP_THREADS.none { thread.contains(it, ignoreCase = true) }) {
            "render() was called on '$thread', which looks like a server event-loop thread. " +
                "It blocks, and the renderer needs this server to answer it — see M5-08."
        }
    }

    override fun isHealthy(): Boolean = try {
        client.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode() == 200
    } catch (_: Exception) {
        false
    }
}
