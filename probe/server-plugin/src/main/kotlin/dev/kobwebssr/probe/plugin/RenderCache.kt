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
class RenderCache(private val ttl: Duration, private val enabled: Boolean) {
    private class Entry(val result: RenderResult.Rendered, val storedAtNanos: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val expiries = AtomicLong()

    /**
     * Returns the cached page, or computes and stores one.
     *
     * Only [RenderResult.Rendered] is stored. A failure must never be cached: a renderer that is
     * down for a second would otherwise serve fallbacks for a whole TTL, and Risk 7's error page
     * would be pinned in front of a page that works.
     */
    fun getOrRender(path: String, render: () -> RenderResult): RenderResult {
        if (!enabled) return render()

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

    fun stats(): String = buildString {
        appendLine("enabled=$enabled ttl=${ttl.toSeconds()}s")
        appendLine("entries=${entries.size}")
        appendLine("hits=${hits.get()} misses=${misses.get()} expired=${expiries.get()}")
    }
}
