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
 * **Correction from M3.** This interface used to carry a `state` field, declared in M1 so that
 * M3 would not have to reshape the seam. M3 did not need it: the composition's saved state has to
 * be inside the document for the client to read it before the bundle runs, so carrying it beside
 * the HTML would only have been a copy. The field is gone rather than left unused — a declared
 * channel that nothing writes to reads as a supported feature.
 */
interface PageRenderer {
    fun render(request: RenderRequest): RenderResult

    /** Whether the renderer is reachable. Used to decide between serving SSR and standing aside. */
    fun isHealthy(): Boolean
}

/**
 * What the renderer is asked to produce, and everything of the visitor's request that reaches it.
 *
 * **M5-02.** Until this milestone the only field was [path], and that was the defect: the renderer
 * built its own URL and its own browser context, so a page reading `?q=` or the visitor's language
 * was rendered for a request nobody had made — and the answer came back with status 200.
 *
 * [cacheKey] is on this class rather than in the cache on purpose. Whatever varies the render must
 * vary the key, and keeping the two in one place is what stops the next field being added to one
 * and forgotten in the other.
 *
 * @param path the path as the visitor asked for it, leading slash included, query string excluded.
 * @param query the visitor's raw query string, without the leading `?`, already stripped of
 *   anything the renderer reserves for itself.
 * @param locale the visitor's preferred language, or null. Reaches the render as both the
 *   `Accept-Language` header and `navigator.language`.
 */
data class RenderRequest(
    val path: String,
    val query: String? = null,
    val locale: String? = null,
) {
    val cacheKey: String get() = "$path\u0000${query.orEmpty()}\u0000${locale.orEmpty()}"
}

sealed interface RenderResult {
    data class Rendered(val html: String) : RenderResult

    /**
     * The renderer could not produce the page. Carries a reason rather than a boolean because the
     * caller has to decide between falling back to Kobweb's own handling and surfacing an error,
     * and "it failed" does not support that decision.
     */
    data class Failed(val reason: String) : RenderResult
}
