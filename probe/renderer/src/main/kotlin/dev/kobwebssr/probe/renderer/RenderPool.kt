package dev.kobwebssr.probe.renderer

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

sealed interface RenderOutcome {
    data class Ok(val html: String) : RenderOutcome
    data class Failed(val reason: String) : RenderOutcome
}

/**
 * **M1-05.** A fixed set of workers behind one queue.
 *
 * Each worker owns its own [Playwright] instance because Playwright's Java binding is not thread
 * safe — an instance belongs to the thread that created it. That is why this is a pool of
 * single-threaded workers rather than a shared browser driven concurrently.
 *
 * The counters exist because of Risk 1 in the research: when concurrency is bounded, the queue is
 * the bottleneck long before the renderer is, and a report that only times the render describes
 * the part that was never the problem. Wait time is therefore recorded separately from render
 * time, and both are reported.
 */
class RenderPool(target: String, private val workers: Int, private val instrumentHydration: Boolean = false) {
    private val queue = ArrayBlockingQueue<Job>(256)

    private val queued = AtomicLong()
    private val completed = AtomicLong()
    private val rejected = AtomicLong()
    private val waitNanos = AtomicLong()
    private val renderNanos = AtomicLong()
    private val maxDepth = AtomicLong()

    private class Job(val path: String, val enqueuedAt: Long, val result: CompletableFuture<RenderOutcome>)

    init {
        repeat(workers) { index ->
            Thread({ work(target, index) }, "renderer-$index").apply { isDaemon = true }.start()
        }
    }

    fun render(path: String): RenderOutcome {
        val job = Job(path, System.nanoTime(), CompletableFuture())
        queued.incrementAndGet()
        if (!queue.offer(job)) {
            rejected.incrementAndGet()
            return RenderOutcome.Failed("render queue is full (${queue.size})")
        }
        maxDepth.updateAndGet { maxOf(it, queue.size.toLong()) }
        return job.result.join()
    }

    fun stats(): String {
        val done = completed.get().coerceAtLeast(1)
        return buildString {
            appendLine("workers=$workers")
            appendLine("queued=${queued.get()} completed=${completed.get()} rejected=${rejected.get()}")
            appendLine("depth_now=${queue.size} depth_max=${maxDepth.get()}")
            appendLine("wait_avg_ms=${waitNanos.get() / done / 1_000_000}")
            appendLine("render_avg_ms=${renderNanos.get() / done / 1_000_000}")
        }
    }

    private fun work(target: String, index: Int) {
        Playwright.create(
            Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")),
        ).use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                println("[renderer] worker $index ready")
                while (true) {
                    val job = queue.take()
                    waitNanos.addAndGet(System.nanoTime() - job.enqueuedAt)
                    val startedAt = System.nanoTime()
                    val outcome = try {
                        snapshot(browser, target, job.path)
                    } catch (e: Exception) {
                        RenderOutcome.Failed("render threw: ${e.message}")
                    }
                    renderNanos.addAndGet(System.nanoTime() - startedAt)
                    completed.incrementAndGet()
                    if (outcome is RenderOutcome.Ok) {
                        println("[renderer] ${job.path} -> ${outcome.html.length} chars in ${(System.nanoTime() - startedAt) / 1_000_000}ms (worker $index)")
                    }
                    job.result.complete(outcome)
                }
            }
        }
    }

    private fun snapshot(browser: Browser, target: String, path: String): RenderOutcome {
        browser.newContext(
            Browser.NewContextOptions().setExtraHTTPHeaders(mapOf(BYPASS_HEADER to "1")),
        ).use { context ->
            context.newPage().use { page ->
                val errors = mutableListOf<String>()
                page.onPageError { errors += it }
                page.navigate("$target$path?_kobwebIsExporting=true&_kobwebColorModeStrategy=BOTH")
                page.bakeStyleSheets()
                page.embedComposedState()
                if (instrumentHydration) page.embedHydrationProbe()
                if (errors.isNotEmpty()) return RenderOutcome.Failed("page crashed: ${errors.first()}")
                val document = Jsoup.parse(page.content())
                document.rejectionReason()?.let { return RenderOutcome.Failed(it) }
                return RenderOutcome.Ok(document.toString())
            }
        }
    }

    /**
     * **M1-07 / Risk 7.** A browser renders an error page as willingly as a real one, so
     * "the navigation did not throw" is not evidence that a page came back. In M1 this bit: with
     * the exported file removed the origin answered 500, the browser drew the error page, and a
     * 254-character snapshot was returned as a success.
     *
     * Each clause below exists because of a case actually observed, and none of them is a
     * general-purpose HTML validator:
     *
     *  * **no Kobweb root** — what an error page looks like;
     *  * **an empty Kobweb root** — what the un-rendered shell looks like, so this also catches a
     *    snapshot taken before the composition ran;
     *  * **an empty `<style>`** — what a page looks like when the CSSOM baking did not happen,
     *    since Compose HTML leaves the tags behind and fills them through the CSSOM. Note the
     *    caveat: a page that legitimately ships an empty `<style>` would be rejected here. The
     *    probe has none, a real site might, and then this clause needs to compare against the
     *    sheet's rule count rather than the tag's text.
     */
    private fun Document.rejectionReason(): String? {
        val root = getElementById("_kobweb-root")
            ?: return "no #_kobweb-root in the result — the origin probably served an error page"
        if (root.html().isBlank()) {
            return "#_kobweb-root is empty — the snapshot was taken before the composition rendered"
        }
        val empty = select("style").count { it.data().isBlank() }
        if (empty > 0) return "$empty empty <style> element(s) — the CSSOM was not baked in"
        return null
    }

    /**
     * **M3-01 / D3.** Pulls the composition's `SaveableStateRegistry` out through the hook the
     * application installs on `window`, and writes it back into the document as a script the
     * client will read on boot.
     *
     * The state travels **inside the HTML** rather than beside it, and that is not a shortcut: it
     * has to be in the document for the client to see it before the bundle runs, so a second
     * channel would only be a copy. This is why `RenderResult` no longer carries a `state` field —
     * see the correction on D3 in the research.
     *
     * A page whose application does not install the hook renders exactly as before. That is the
     * intended behaviour: state transfer is opt-in per application, not a property of the renderer.
     */
    private fun Page.embedComposedState() {
        // language=javascript
        evaluate(
            """
            (function () {
                if (typeof window.__kobwebSsrSaveState !== 'function') return;
                const state = window.__kobwebSsrSaveState();
                if (!state || state === '{}') return;
                const script = document.createElement('script');
                // Embedded as a JS string literal, so a '</script>' inside a saved value cannot
                // close the tag it is sitting in.
                script.textContent = 'window.KOBWEB_SSR_STATE = ' + JSON.stringify(state) + ';';
                document.head.appendChild(script);
            })();
            """.trimIndent(),
        )
    }

    /**
     * **M3-03 / M3-04.** An optional probe that counts what the client does to the server-rendered
     * tree once the bundle boots.
     *
     * Injected by the renderer rather than written into the application, because the question is
     * about the framework's behaviour and instrumenting the application would change the subject.
     * It is off by default: it is a measuring instrument, not a feature.
     */
    private fun Page.embedHydrationProbe() {
        // language=javascript
        evaluate(
            """
            (function () {
                const script = document.createElement('script');
                script.textContent = `
                  // Installed while <head> is being parsed, before the bundle at the end of <body>
                  // has run. Observing on DOMContentLoaded is too late: the Kobweb bundle wipes and
                  // re-renders before that event fires, so a probe started there sees a settled
                  // tree and reports zero — which is what the first version of this probe did.
                  window.__hydrationStats = { removed: 0, added: 0, batches: 0, firstRemovalAtMs: null };
                  const t0 = performance.now();
                  new MutationObserver((records) => {
                    const s = window.__hydrationStats;
                    for (const r of records) {
                      // The HTML parser only ever adds. A removal inside the Kobweb root is the
                      // client throwing away what the server sent.
                      const inRoot = r.target.id === '_kobweb-root' ||
                        (r.target.closest && r.target.closest('#_kobweb-root'));
                      if (!inRoot) continue;
                      s.batches += 1;
                      s.added += r.addedNodes.length;
                      if (r.removedNodes.length) {
                        if (s.firstRemovalAtMs === null) s.firstRemovalAtMs = Math.round(performance.now() - t0);
                        s.removed += r.removedNodes.length;
                      }
                    }
                  }).observe(document.documentElement, { childList: true, subtree: true });
                `;
                document.head.insertBefore(script, document.head.firstChild);
            })();
            """.trimIndent(),
        )
    }

    /**
     * Compose HTML creates empty `<style>` nodes and fills them through the CSSOM, so a document
     * saved as-is carries the tags and none of the rules. Writing each sheet's own rules back into
     * its owner node makes the markup self-describing before any JavaScript runs.
     *
     * This is `KobwebExportTask.takeSnapshot`'s script, kept deliberately identical: M1-02 compares
     * this renderer's output against `kobweb export` for the same page, and an "improvement" here
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
