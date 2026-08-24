# probe — the M0 fixture

A minimal Kobweb site plus a third-party server plugin, built to answer one question: **can a
`KobwebServerPlugin` take over the route of a real page without patching Kobweb?**

Everything here exists to make one measurement honest. The pages and the plugin each stamp a
marker into the response body — `RENDERED BY THE CLIENT` or `RENDERED BY THE SERVER PLUGIN` — so
who answered a request is read off the body rather than inferred.

The findings live in [`../docs/research/research-architecture.md`](../docs/research/research-architecture.md)
§1.6; the milestone is [`../BACKLOG.md`](../BACKLOG.md) M0.

## Pinned versions

Every number in the M0 findings is against these and nothing else.

| | |
|---|---|
| Kobweb | 0.25.1 (CLI 0.9.18, used only for reading templates) |
| Compose HTML / Runtime | 1.11.1 / 1.12.0 |
| Kotlin | 2.4.10 |
| Ktor (the server's own) | 3.5.0 |
| Gradle | 9.6.1 |
| JDK | 21 (GraalVM 21.0.9). **Not the machine default**, which is 25 |

## Running it

`JAVA_HOME` must point at a JDK 21; the default 25 is not what this was measured on.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

**The export has to happen with the plugin off, and that is not a detail.** `kobwebExport` drives
a real browser against a running Kobweb server, so a plugin that is loaded while it runs gets its
own responses baked into the static snapshots — and the baked file looks like a plausible page,
because what lands in it is the browser's plain-text viewer rendering of the plugin's reply. That
is how this probe produced a confident false positive on its first run. Hence the `probePlugin`
property: absent during export, present when running.

```bash
# 1. honest export — no plugin
./gradlew :site:kobwebStop
rm -rf site/.kobweb/server/plugins/* site/.kobweb/site
./gradlew :site:kobwebExport -PkobwebReuseServer=false -PkobwebEnv=DEV -PkobwebExportLayout=STATIC
./gradlew :site:kobwebStop   # export leaves its own server running

# every exported page must say CLIENT before you trust anything below
grep -o 'RENDERED BY THE [A-Z ]*' site/.kobweb/site/*.html

# 2. run with the plugin, in either layout
./gradlew :site:kobwebStart -PprobePlugin -PkobwebEnv=PROD -PkobwebRunLayout=STATIC
./gradlew :site:kobwebStart -PprobePlugin -PkobwebEnv=DEV  -PkobwebRunLayout=FULLSTACK
```

## What each route is for

| Route | Claimed by the plugin via | Question |
|---|---|---|
| `/ssr-probe` | `routing { get }` | is the plugin loaded at all |
| `/ssr-probe/fresh` | `routing { get }` | can it serve a path no page owns |
| `/collide` | `routing { get }` | **M0-03** — against the page `CollidePage` |
| `/collide2` | `intercept(ApplicationCallPipeline.Plugins)` | **M0-04** — same collision, earlier hook |
| `/nope` | nothing | control: what an unclaimed path does per layout |

## Gotchas paid for here

- The artifact is **`com.varabyte.kobweb:kobweb-server-plugin`**, not `server-plugin`, and the
  `ServiceLoader` descriptor must be named
  `META-INF/services/com.varabyte.kobweb.server.plugin.KobwebServerPlugin`. Kobweb's own
  `backend/server-plugin/README.md` gets both wrong, and also shows the interface method as
  `override fun Application.configure()` when it is `fun configure(application: Application)`.
- `kobwebSyncServerPluginJars` is a `Sync` into `.kobweb/server/plugins`, so a jar copied there by
  hand does not survive the next start. It also did not clear the directory when the configuration
  went empty — the jar had to be deleted by hand.
- `kobwebExport -PkobwebReuseServer=false` leaves its server running when it finishes. The next
  `kobwebStart` then fails with a want/current environment mismatch rather than with anything that
  mentions the export.
