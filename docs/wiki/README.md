# OmniTAK-Android wiki

Reference for people and AI agents working in this repository. Everything here was written from reading the code at commit `53f554e` (0.43.0), not from comments or READMEs, and each page names the files it describes. When a page and the code disagree, the code wins; fix the page.

## Pages

| Page | Read it when |
|---|---|
| [security-audit.md](security-audit.md) | Before touching input parsing, TLS, deep links, mesh, or UAS. Full 2026-09-14 audit with findings, remediation status per finding, and the complete list of network destinations. |
| [build-and-release.md](build-and-release.md) | Building, testing, signing, CI, dependency list, secrets handling. |
| [networking-and-tls.md](networking-and-tls.md) | TAK server connections, trust policy, certificate enrollment, credential storage, reconnect. |
| [parsers-and-imports.md](parsers-and-imports.md) | CoT XML model, deep-link and QR onboarding flows, KML / raster / MBTiles / iconset import, persistence formats. |
| [mesh-networking.md](mesh-networking.md) | Meshtastic and MeshCore transports, protobuf wire formats, relay gateway, Remote ID, GYB sensor. |
| [map-rendering.md](map-rendering.md) | MapLibre style, layer order, why annotations are used instead of GeoJSON layers, basemap providers. |
| [cesium-3d-globe.md](cesium-3d-globe.md) | WebView engine, the Kotlin to JavaScript bridge contract, CDN resources. |
| [uas-and-video.md](uas-and-video.md) | MAVLink connection, drone commands, mission upload, video pipelines, ONVIF, FAA airspace. |
| [offline-maps-and-coords.md](offline-maps-and-coords.md) | Offline region downloads, MBTiles schema, coordinate systems. |
| [plugin-sdk.md](plugin-sdk.md) | Plugin contract, how to add a plugin, why plugins are compile-time only. |
| [ui-conventions-and-testing.md](ui-conventions-and-testing.md) | Composition root, navigation routes, UI conventions, i18n, test layout. |

Existing root-level docs that complement these: `README.md`, `PREFERENCES.md` (preference keys and `tak://` verbs), `PARITY.md` (iOS parity tracker with GAP ids), `DESIGN_TOKENS.md`, `docs/PLUGIN_AUTHORING.md`, `docs/QUICKSTART.md`, `PRIVACY.md`, `SECURITY.md`, `CONTRIBUTING.md`.

## What the app is

An open-source Team Awareness Kit (TAK) client for Android: Kotlin, Jetpack Compose, MapLibre. It speaks Cursor-on-Target (CoT) XML over TLS to any TAK Server, bridges Meshtastic and MeshCore LoRa radios, controls MAVLink drones, and renders MIL-STD-2525 symbology. The companion iOS client is OmniTAK-iOS; `PARITY.md` tracks feature gaps between them with `GAP-nnn` ids that appear in commit messages.

## Architecture in one screen

```
app/src/main/kotlin/soy/engindearing/omnitak/mobile/
  OmniTAKApp.kt        Application = composition root. Lazy singletons for every store and manager. appScope collectors.
  MainActivity.kt      Single activity. Deep links (tak:// atak:// omnitak://), onboarding gate, AppNav().
  data/                Models, codecs, parsers, transports, persistence. No Compose.
    net/TakTls.kt          TLS policy
    TAKConnection.kt       one socket per server
    TAKServerStore.kt      DataStore + Keystore-backed secrets
    UserPrefs.kt           preferences (see PREFERENCES.md)
    CoTParser.kt, CotXml.kt, ChatXml.kt      CoT XML in and out
    Meshtastic*, MeshCore*, TakPacket*       mesh protocols
    KmlVectorOverlay, RasterOverlay, MBTilesOverlay   imports
    uas/ onvif/ airspace/ offline/ remoteid/ gyb/ symbology/ discovery/
  domain/              State stores and managers. StateFlow out, suspend functions in.
    ServerManager.kt       connections + sendCoT
    ContactStore, ChatStore, DrawingStore, LocalMarkerStore   rosters
    SelfPositionBroadcaster.kt   PPLI loop
    MeshtasticManager, MeshCoreManager, MeshServerRelay (gateway, off by default)
    UASManager.kt          drones
  ui/
    navigation/AppNav.kt       routes + toolbar
    screens/MapScreen.kt       the hub (about 2500 lines)
    components/TacticalMap.kt  MapLibre wrapper
    components/CesiumMapView.kt   3D WebView engine
    plugin/AppPluginHost.kt    plugin seam
    theme/
  i18n/Loc.kt, LocStrings.kt   in-code string catalogues
plugins/plugin-sdk, example-adsb, example-diagnostics    compile-time plugin modules
app/src/main/assets/   cesium_scene.html, cot_types.json, milstd/*.svg, fema/*.svg
```

Data flow in one sentence: sockets and radios produce frames; `data/` parsers turn them into `CoTEvent` / `ChatMessage`; `domain/` stores hold them as `StateFlow`; `MapScreen` and friends render them; user actions build CoT through `CotXml` / `CotBuilders` and send via `ServerManager.sendCoT` (all servers) or `activeMeshManager.sendCoTOverMesh`.

## Conventions agents must follow

- Screens get dependencies with `LocalContext.current.applicationContext as OmniTAKApp`. Do not add a DI framework.
- Anything going into CoT XML passes through `CotXml.escape`. Anything going into `evaluateJavascript` passes through `org.json`.
- New network or CoT-parsing code needs a JVM unit test under `app/src/test`. Tests may call `android.util.Log`; they need real `org.json` / `kxml2` for JSON and XML.
- Plugins stay compile-time. Never add class loading.
- The map screen is full-screen and sacred; editors are bottom sheets or dialogs.
- Follow `CONTRIBUTING.md`: Compose, coroutines and Flow, no `!!` in production, 4-space indent, no wildcard imports, imperative commit subjects with the `GAP-nnn` or issue reference when applicable.
- Treat every inbound byte from a TAK server, mesh node, drone, or camera as hostile. The security audit lists where that assumption currently breaks.
- Secrets: never commit `local.properties`, `keystore.properties`, `.p12`, `.pem`, or a Cesium Ion token. Release builds silently fall back to debug signing when the keystore is absent.

## Verifying a change

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Instrumented tests (`connectedDebugAndroidTest`) need a device or emulator; MapLibre renders through native GL and cannot be exercised from JVM tests.
