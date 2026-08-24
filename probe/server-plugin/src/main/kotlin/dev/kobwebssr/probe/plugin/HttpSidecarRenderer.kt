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
