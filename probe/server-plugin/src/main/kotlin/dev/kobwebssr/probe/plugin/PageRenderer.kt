package dev.kobwebssr.probe.plugin

/**
 * The seam of **D2**: everything that depends on *how* HTML is produced lives behind this and
 * nothing else does.
 *
 * There are three implementations planned and only one written, which is the honest state of
 * affairs — an interface with a single implementation proves nothing, and M2 exists to find out
 * whether this shape survives contact with a second one:
 *
 *  * [HttpSidecarRenderer] over a headless browser (M1);
 *  * the same over a Node process running the app against a DOM emulation (M2);
 *  * eventually an in-process call to `renderToString`, if JetBrains ship a JVM target for
 *    Compose HTML — at which point this interface is the only thing that has to still fit.
 *
 * [RenderResult.state] is a placeholder in M1: the browser renderer has nowhere to get it from
 * yet. It is declared now because M3 transfers state through `SaveableStateRegistry`, and finding
 * out in M3 that the seam has no room for it would mean changing the seam — which is exactly the
 * failure M2 is meant to catch early.
 */
interface PageRenderer {
    fun render(request: RenderRequest): RenderResult

    /** Whether the renderer is reachable. Used to decide between serving SSR and standing aside. */
    fun isHealthy(): Boolean
}

/**
 * @param path the path as the visitor asked for it, leading slash included, query string excluded.
 */
data class RenderRequest(val path: String)

sealed interface RenderResult {
    /** @param state serialised composition state; always null until M3. */
    data class Rendered(val html: String, val state: String? = null) : RenderResult

    /**
     * The renderer could not produce the page. Carries a reason rather than a boolean because the
     * caller has to decide between falling back to Kobweb's own handling and surfacing an error,
     * and "it failed" does not support that decision.
     */
    data class Failed(val reason: String) : RenderResult
}
