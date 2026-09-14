# OmniTAK-Android

Open-source TAK client for Android (Kotlin, Jetpack Compose, MapLibre). Read `docs/wiki/README.md` first; it is the index to the codebase wiki written for AI agents, with an architecture map, per-subsystem pages, and the 2026-09-14 security audit.

Quick rules:
- Dependencies come from `OmniTAKApp` via `LocalContext.current.applicationContext as OmniTAKApp`.
- Escape everything that goes into CoT XML with `CotXml.escape`; escape everything that goes into the Cesium WebView with `org.json`.
- Treat all inbound data from servers, radios, drones, and cameras as hostile.
- Plugins are compile-time modules only. No dynamic code loading.
- New networking or parsing code needs a JVM unit test. Run `./gradlew testDebugUnitTest`.
- Never commit secrets (`local.properties`, `keystore.properties`, certificates, Cesium Ion tokens).
- Layout and style rules are in `CONTRIBUTING.md`; iOS parity gaps are tracked in `PARITY.md` with `GAP-nnn` ids.

## Fork-only rule

This checkout is the fork `bjself/OmniTAK-Android` (remote `origin`). Push branches and open pull requests against `main` of this fork. Do not interact with the upstream repository `engindearing-projects/OmniTAK-Android` in any way: no pull requests targeting it, no issues, comments, or fork syncs. When using `gh pr create`, always pass `--repo bjself/OmniTAK-Android --base main`, because `gh` defaults to a fork's parent.
