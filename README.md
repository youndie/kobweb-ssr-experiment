# kobweb-ssr

Server-side rendering for [Kobweb](https://github.com/varabyte/kobweb) sites.

Kobweb builds websites out of `@Composable` functions on top of Compose HTML. It has no SSR: a
page arrives either as an empty shell or as a build-time snapshot, and the client then throws the
snapshot away and rebuilds the tree from scratch. This repository is the work of putting a real
server-rendered page in front of that.

**There is no code yet.** What exists is the research that decides whether to write it, which
path to take, and what stays worth having even if JetBrains ships a JVM target for Compose HTML.

Start here: [docs/research/research-architecture.md](docs/research/research-architecture.md).
Then [BACKLOG.md](BACKLOG.md).

The short version:

- a JVM target for Compose HTML is not arriving soon — the August 2026 JetBrains post says so
  itself, and there is no YouTrack issue behind it;
- a custom `Applier` over `compose-runtime` does not exist as a path: Compose HTML writes
  attributes, styles, classes and listeners straight into the browser node, never through the
  `Applier`;
- so: a sidecar renderer behind a replaceable seam, wired in through Kobweb's existing
  `KobwebServerPlugin` extension point, with no fork of anything.

Documentation is written in Russian; code, commit messages and everything inside source files are
in English.
