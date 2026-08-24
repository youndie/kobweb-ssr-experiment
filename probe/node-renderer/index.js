'use strict';

const http = require('http');
const { JSDOM, VirtualConsole } = require('jsdom');

// Must match the header the server plugin honours. Without it the arrangement eats itself: the
// plugin intercepts a page request, asks this renderer for the page, and this renderer asks the
// same server for it again.
const BYPASS_HEADER = 'X-Kobweb-Ssr-Bypass';

// Kept character-for-character identical to the browser renderer's copy, which in turn is
// KobwebExportTask's. Any "improvement" on one side shows up as a difference in the comparison
// this module exists to make.
const BAKE_STYLESHEETS = `
for (let s = 0; s < document.styleSheets.length; s++) {
    var stylesheet = document.styleSheets[s]
    stylesheet = stylesheet instanceof CSSStyleSheet ? stylesheet : null;
    if (stylesheet != null && stylesheet.href == null) {
        var styleNode = stylesheet.ownerNode
        styleNode = styleNode instanceof Element ? styleNode : null
        if (styleNode != null && styleNode.innerHTML == '') {
            const rules = []
            for (let r = 0; r < stylesheet.cssRules.length; ++r) {
                rules.push(stylesheet.cssRules[r].cssText.replace(/(\\n)/gm, ''))
            }
            styleNode.innerHTML = rules.join('')
        }
    }
}
`;

/**
 * Everything the bundle needs that jsdom does not provide, installed before any script runs.
 *
 * This function is the honest cost of the Node path. A browser needs none of it, and two of the
 * four entries are not faithful — see README.
 */
function installPolyfills(window) {
  // Kobweb's dev server pushes live-reload events. A renderer has no use for them, but the bundle
  // constructs one unconditionally and an absent global is an uncaught error.
  window.EventSource = class {
    addEventListener() {}
    close() {}
  };

  window.eval(`
    (function () {
      // jsdom throws its URL errors from the Node realm, so inside the page realm they are not
      // 'instanceof Error' and Kotlin's 'catch (e: Throwable)' walks straight past them. Kobweb's
      // Route uses 'new URL(path)' throwing as its test for "this is a relative route", so without
      // re-throwing in this realm every route registration escapes and the router registers
      // nothing at all.
      const Native = URL;
      const Wrapped = function (...args) {
        try { return new Native(...args); }
        catch (e) { throw new TypeError(String((e && e.message) || e)); }
      };
      Wrapped.prototype = Native.prototype;
      if (Native.createObjectURL) Wrapped.createObjectURL = Native.createObjectURL.bind(Native);
      if (Native.revokeObjectURL) Wrapped.revokeObjectURL = Native.revokeObjectURL.bind(Native);
      window.URL = Wrapped;

      window.CSS = {
        // Faithful enough: the CSS.escape algorithm over the characters that appear in class names.
        escape(value) {
          return String(value).replace(/[^a-zA-Z0-9_\\u00a1-\\uffff-]/g, (c) =>
            c === '\\0' ? '\\ufffd' : '\\\\' + c);
        },
        // NOT faithful. Answering this needs a style engine. Kobweb calls it to choose among
        // fallback values, so always saying yes can make this renderer emit CSS a browser would
        // not have emitted — a divergence with no symptom.
        supports() { return true; },
      };
    })();
  `);
}

/**
 * Same clauses as the browser renderer's check, and for the same reasons: a DOM emulation reports
 * success just as readily as a browser does, and this one is known to leave stylesheets empty.
 */
function rejectionReason(document) {
  const root = document.getElementById('_kobweb-root');
  if (!root) return 'no #_kobweb-root in the result — the origin probably served an error page';
  if (!root.innerHTML.trim()) {
    return '#_kobweb-root is empty — the snapshot was taken before the composition rendered';
  }
  const empty = [...document.querySelectorAll('style')].filter((s) => !s.textContent.trim()).length;
  if (empty > 0) return `${empty} empty <style> element(s) — the CSSOM was not baked in`;
  return null;
}

async function render(target, path, settleMs, allowIncomplete) {
  const url = `${target}${path}?_kobwebIsExporting=true&_kobwebColorModeStrategy=BOTH`;
  const response = await fetch(url, { headers: { [BYPASS_HEADER]: '1' } });
  const shell = await response.text();

  const virtualConsole = new VirtualConsole();
  const errors = [];
  virtualConsole.on('jsdomError', (e) => errors.push(e.message));

  const dom = new JSDOM(shell, {
    url,
    runScripts: 'dangerously',
    resources: 'usable',
    pretendToBeVisual: true,
    virtualConsole,
    beforeParse: installPolyfills,
  });

  try {
    // A fixed wait rather than a signal, and that is a real weakness of this implementation: there
    // is nothing to wait *for*. A browser gives you load and network-idle events; here the
    // composition simply settles at some point. Too short and the page is half-built, too long and
    // every render pays for it.
    await new Promise((resolve) => setTimeout(resolve, settleMs));
    if (errors.length) return { ok: false, reason: `page crashed: ${errors[0]}` };
    dom.window.eval(BAKE_STYLESHEETS);
    const reason = rejectionReason(dom.window.document);
    // The escape hatch exists for M2-03 only: it lets the measurement compare like with like even
    // though this renderer's output is known to be missing 61 CSS rules. Any number taken with it
    // on describes a page that would not be served.
    if (reason && !allowIncomplete) return { ok: false, reason };
    if (reason) console.log(`[node-renderer] SERVING INCOMPLETE OUTPUT: ${reason}`);
    return { ok: true, html: dom.serialize() };
  } finally {
    dom.window.close();
  }
}

function main() {
  const arg = (flag, fallback) => {
    const i = process.argv.indexOf(flag);
    return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
  };
  const target = arg('--target', 'http://localhost:8080');
  const port = Number(arg('--port', '7898'));
  const settleMs = Number(arg('--settle-ms', '2500'));
  const allowIncomplete = process.argv.includes('--allow-incomplete');

  const stats = { completed: 0, failed: 0, renderMs: 0 };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const reply = (code, type, body) => {
      res.writeHead(code, { 'Content-Type': type });
      res.end(body);
    };
    if (url.pathname === '/health') return reply(200, 'text/plain', 'ok');
    if (url.pathname === '/stats') {
      const done = Math.max(stats.completed, 1);
      return reply(200, 'text/plain',
        `completed=${stats.completed} failed=${stats.failed}\nrender_avg_ms=${Math.round(stats.renderMs / done)}\n`);
    }
    if (url.pathname !== '/render') return reply(404, 'text/plain', 'not found');

    const path = url.searchParams.get('path');
    if (!path) return reply(400, 'text/plain', "missing 'path'");

    const startedAt = Date.now();
    try {
      const result = await render(target, path, settleMs, allowIncomplete);
      const tookMs = Date.now() - startedAt;
      stats.completed += 1;
      stats.renderMs += tookMs;
      if (result.ok) {
        console.log(`[node-renderer] ${path} -> ${result.html.length} chars in ${tookMs}ms`);
        return reply(200, 'text/html; charset=utf-8', result.html);
      }
      stats.failed += 1;
      console.log(`[node-renderer] ${path} REJECTED: ${result.reason}`);
      return reply(502, 'text/plain', result.reason);
    } catch (e) {
      stats.failed += 1;
      console.log(`[node-renderer] ${path} FAILED: ${e.message}`);
      return reply(502, 'text/plain', `render failed: ${e.message}`);
    }
  });

  server.listen(port, () => {
    console.log(`[node-renderer] listening on http://localhost:${port}, rendering against ${target}, settle ${settleMs}ms`);
  });
}

main();
