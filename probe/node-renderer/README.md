# node-renderer — the second implementation of the seam

Runs the same Kobweb bundle in Node under jsdom and speaks the renderer protocol of
[`../renderer`](../renderer), so the server plugin reaches it by changing one URL.

```bash
npm install
node index.js --target http://localhost:8080 --port 7898
```

## Why jsdom rather than happy-dom

Chosen by measurement, not by popularity. Feeding this project's real page CSS (69134 characters,
271 `@layer` occurrences, 24 `@media`, 258 custom properties, 27 `:hover`) through each
emulation's CSSOM and reading it back:

| | round-tripped | `@layer` | `@media` | `@keyframes` | custom props | `:hover` |
|---|---|---|---|---|---|---|
| chromium (the input) | 69134 | 271 | 24 | 2 | 258 | 27 |
| jsdom 29.1.1 | 69388 | 271 | 24 | 2 | 258 | 27 |
| happy-dom 20.11.6 | 10824 | **0** | 23 | 2 | **83** | **0** |

happy-dom drops 84% of the stylesheet, including every cascade layer, which is not a detail here:
Silk puts everything in `@layer`.

## What it costs that a browser does not

Four things the bundle needs and jsdom does not provide. The list is the point — each is a place
where this renderer can diverge from a browser without saying so.

| Polyfill | Faithful? |
|---|---|
| `EventSource` | stub. Kobweb's dev server uses it for live reload; a renderer has no use for it |
| `URL` re-thrown in the page realm | **semantic**. jsdom throws its `URL` errors from the Node realm, so inside the page they are not `instanceof Error` and Kotlin's `catch (e: Throwable)` does not catch them. Kobweb's `Route` deliberately uses that throw as a validity test, so without this the router registers nothing |
| `CSS.escape` | faithful |
| `CSS.supports` | **a guess — always true**. Answering it properly needs a style engine. Kobweb uses it to pick among fallback values, so a wrong answer changes the CSS that gets emitted |

## Known divergence from the browser

Two of the five `<style>` elements come out empty: 61 rules missing, the whole `kobweb-compose`
layer (`.kobweb-box`, `place-items`, …) and the breakpoint display styles. The DOM is identical —
same 22 elements, same 7 under the Kobweb root, same 8 classes, same text — so nothing about the
page looks wrong until you compare the CSS.

The cause is **not** jsdom's CSSOM. Tested directly, jsdom accepts `insertRule` for plain rules,
`@layer` blocks and statements, `@scope`, `@media`, nested `insertRule` on grouping rules, and
compose-html's own pattern of inserting an empty rule and then mutating its `.style`. Why those
two stylesheets end up empty is unresolved — see the backlog.

The M1-07 check in the browser renderer rejects exactly this shape, so the defect is caught rather
than served.
