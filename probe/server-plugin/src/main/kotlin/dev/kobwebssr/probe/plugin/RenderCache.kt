package dev.kobwebssr.probe.plugin

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * **M1-04.** A time-bounded cache in front of [PageRenderer].
 *
 * Not an optimisation. Rendering costs the better part of a second (research §1.7) and
 * concurrency is bounded by the renderer pool (Risk 1), so throughput per instance is the hit
 * rate times whatever the pool can do — the same shape Kilua arrived at with its ten-minute
 * expiry. Treating the cache as a nice-to-have would mean measuring a configuration nobody would
 * ever run.
 *
 * Two properties exist for the sake of the measurement rather than for operations:
 *
 *  * `kobwebssr.cache.enabled=false` turns it off, because M1-06 has to measure both
 *    configurations. A switch that is only ever observed in one position tells you nothing about
 *    what it does;
 *  * [stats] is served over HTTP, because a cache with no hit counter is indistinguishable from a
 *    cache that never hits — and from no cache at all.
 */
class RenderCache(
    private val ttl: Duration,
    private val enabled: Boolean,
    private val buildFingerprint: () -> String = ::siteBuildFingerprint,
) {
    private class Entry(val result: RenderResult.Rendered, val storedAtNanos: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val expiries = AtomicLong()
    private val invalidations = AtomicLong()
    private val fingerprint = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastFingerprintCheck = AtomicLong(0)

    /**
     * Returns the cached page, or computes and stores one.
     *
     * Only [RenderResult.Rendered] is stored. A failure must never be cached: a renderer that is
     * down for a second would otherwise serve fallbacks for a whole TTL, and Risk 7's error page
     * would be pinned in front of a page that works.
     */
    fun getOrRender(path: String, render: () -> RenderResult): RenderResult {
        if (!enabled) return render()

        discardIfSiteChanged()
        val now = System.nanoTime()
        entries[path]?.let { entry ->
            if (now - entry.storedAtNanos < ttl.toNanos()) {
                hits.incrementAndGet()
                return entry.result
            }
            expiries.incrementAndGet()
            entries.remove(path, entry)
        }

        misses.incrementAndGet()
        val result = render()
        if (result is RenderResult.Rendered) {
            entries[path] = Entry(result, System.nanoTime())
        }
        return result
    }

    fun invalidate(path: String?): Int {
        if (path == null) {
            val size = entries.size
            entries.clear()
            return size
        }
        return if (entries.remove(path) != null) 1 else 0
    }

    /**
     * **M5-06.** Throws the cache away when the site itself changes.
     *
     * Without this a redeploy keeps serving the previous build's HTML for a whole TTL, and the
     * symptom is the worst kind: the page is valid, the status is 200, and only the content is
     * from yesterday. Time-based expiry does not help — it decides *when* an entry dies, not
     * whether it still describes the site that is running.
     *
     * Checked at most once a second, because the alternative is a stat call on every cache hit and
     * a hit is supposed to cost microseconds.
     */
    private fun discardIfSiteChanged() {
        val now = System.nanoTime()
        if (now - lastFingerprintCheck.get() < FINGERPRINT_INTERVAL_NANOS) return
        lastFingerprintCheck.set(now)
        val current = buildFingerprint()
        val previous = fingerprint.getAndSet(current)
        if (previous != null && previous != current) {
            val discarded = entries.size
            entries.clear()
            invalidations.incrementAndGet()
            println("[ssr-probe] the site changed; discarded $discarded cached page(s)")
        }
    }

    fun stats(): String = buildString {
        appendLine("enabled=$enabled ttl=${ttl.toSeconds()}s")
        appendLine("entries=${entries.size}")
        appendLine("hits=${hits.get()} misses=${misses.get()} expired=${expiries.get()}")
        appendLine("invalidations=${invalidations.get()}")
    }
}

private const val FINGERPRINT_INTERVAL_NANOS = 1_000_000_000L

/**
 * A cheap stand-in for "which build is this".
 *
 * Looks at whichever of the site's outputs happen to exist — the development bundle, the exported
 * index, the route metadata — and combines size and modification time. It does not need to be a
 * hash: it only has to change when the site does.
 *
 * If none of them is on disk it returns a constant, and then staleness cannot be detected at all.
 * That is a real hole rather than a hypothetical one: a deployed site ships `.kobweb/site` and not
 * `build/`, so which candidates exist depends on how the thing was deployed.
 */
internal fun siteBuildFingerprint(): String = listOf(
    "build/kotlin-webpack/js/developmentExecutable/probe.js",
    ".kobweb/site/index.html",
    "build/processedResources/js/main/kobweb/metadata/frontend.json",
).mapNotNull { path ->
    java.io.File(path).takeIf { it.isFile }?.let { "${it.name}:${it.length()}:${it.lastModified()}" }
}.joinToString("|").ifEmpty { "unknown" }
