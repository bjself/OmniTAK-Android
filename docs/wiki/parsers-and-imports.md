# CoT model, parsers, onboarding flows, imports, and persistence

Base package: `app/src/main/kotlin/soy/engindearing/omnitak/mobile/`.

## CoT data model

```xml
<event version="2.0" uid type time start stale how>
  <point lat lon hae ce le/>
  <detail> ... </detail>
</event>
```

`CoTParser.parse` (`data/CoTParser.kt`) is a flat `XmlPullParser` loop that returns null on any throwable. Mapping to `CoTEvent` (`data/CoTEvent.kt`):

| XML | Field |
|---|---|
| `event@uid/type/time/stale` | `uid`, `type`, `timeIso`, `staleIso` |
| `point@lat/lon/hae/ce/le` | `lat`, `lon`, `hae` (0.0), `ce` / `le` (9999999) |
| `detail/contact@callsign` | `callsign` |
| `detail/__group@name,role` | `teamName`, `teamRole` (drives `displayColor`, team overrides affiliation) |
| `detail/remarks` | `remarks` |
| `detail/usericon@iconsetpath` | `iconsetPath` |
| `detail/color@argb` | `colorArgb` |
| `detail/track@course` | `courseHeading` |
| whole string | `rawXml` |

`uid`, `type`, `lat`, `lon` are required. Ingest adds `receivedAtMs` and `source` (`ContactStore.ingest`). Affiliation is the second dash token (`a-f-...` is FRIEND), battle dimension the third. `CoTAge` buckets freshness (fresh under 1 min, aging to 5 min, stale).

GeoChat (`type=b-t-f`) is parsed separately by `ChatXml.parse` from `__chat@senderCallsign,chatroom,id`, `chatgrp@uid0,uid1`, `link@uid,parent_callsign`, `dest@callsign`, and `remarks`. `parse` returns null on malformed XML. Parsed messages are never `isFromSelf` (only `ChatStore.markOutgoing` asserts ownership), and when a `selfUid` is supplied a frame from our own uid is dropped as an echo or spoof.

All outbound envelopes go through `CotXml.buildEvent` (`data/CotXml.kt`), which escapes `uid`, `type`, `how` but not the timestamp attributes. `CotXml.escape` covers the five XML entities. `CotBuilders` (delete tombstones, rebuild with dest, UAS PLI) and `MilitaryReports.buildReportEvent` use it.

## Onboarding flows

| Flow | Path | User confirmation |
|---|---|---|
| Profile QR / `omnitak://profile?d=<base64url(gzip(json))>` | camera or in-app scan -> `ProfileQrCodec.decode` (gunzip capped at 64 KiB) -> `ImportPreviewDialog` -> `ConfigProfileStore.saveProfile` + `apply` (prefs written, servers merged without secrets, `allowUntrustedTls` forced false) | yes |
| Enrollment link `tak://.../enroll?host=&username=&token=[&enrollport=][&trust=][&name=]` | `DeepLinkImport.parseEnrollLink` (token becomes password, `useTLS=true`, `trustSelfSigned` false unless `trust=true`) -> `MainActivity.enrollFromDeepLink` or `ServerQrScanScreen.enrollAndAdd` -> `OmniTAKApp.pendingServerImport` -> `ServerImportConfirmDialog` -> `ServerOnboarding.addConfirmed` -> `CSREnrollmentService.enroll` -> `.p12` + CA PEM into `CertVault` -> `ServerManager.addServer` -> connect | yes (dialog shows endpoint, transport, username, warnings) |
| Connect link `atak://...?host=&port=&tls|proto=[&username=&password=]` | `DeepLinkImport.parseServerConfig`; if credentials and TLS, goes to enrollment; else cert-less add. Both go through the same confirmation dialog. `useTLS` defaults to `port == 8089` | yes |
| Manual Quick Connect (`EnrollServerScreen`) | same enroll call, `trustSelfSigned` defaults false with a red warning | form |
| Manual Add (`AddServerScreen`) | form, optional `.p12` via `CertVault.import`, basic auth, mDNS prefill | form |
| Data package sideload (`DataPackageBootstrap`) | zips in `getExternalFilesDir("import")` parsed for `server.pref` + `.p12`, added on launch | none |

`MainActivity` is exported with a BROWSABLE VIEW filter for `tak`, `atak`, `omnitak`, so web pages, other apps, NFC tags, and the OS camera can all trigger these. `PREFERENCES.md` at the repo root documents the `tak://` verbs and the preference keys that `/preference` links can set.

## Import pipelines

| Input | Path | Output |
|---|---|---|
| KML / KMZ vector | `KmlVectorOverlayStore.importKml` -> `openKmlStream` (KMZ: first `.kml` entry) -> `KmlGeoJsonConverter.convert` streams Point / LineString / Polygon, drops (0,0) and NaN | `filesDir/kml_overlays/<uuid>.geojson` + `overlays.json` |
| KML / KMZ GroundOverlay raster | `RasterOverlayStore.importGroundOverlay` -> `unzipKmz` (all entries in RAM) -> `GroundOverlayParser` -> image matched by href | `filesDir/raster_overlays/<uuid>.<ext>` + `rasters.json` |
| GeoTIFF | `importGeoTIFF` -> `GeoTIFFParser.bounds` (tags 256 / 257 / 33550 / 33922 / 34264 / 34735, EPSG 4326 / 3857) -> NGA `TiffReader` -> downsample to 4096 px | PNG |
| GeoPDF | `importGeoPDF` -> `GeoPDFParser.bounds` (`/GPTS` in raw or Flate streams, 32 / 64 MiB caps) -> `PdfRenderer` page 1 | PNG |
| MBTiles / GeoPackage | `MBTilesOverlayStore.importTileSet` -> copy to `filesDir/mbtiles/<uuid>` -> open read-only -> `MBTilesServer.register` | raster source at `http://127.0.0.1:<port>/<id>/{z}/{x}/{y}` |
| Iconset zip (ATAK iconset) | `IconPackImporter.importStream` -> all entries in RAM -> `IconsetPackParser.parse(iconset.xml)` -> images written to `filesDir/iconpacks/<uid>/<filename>` -> `IconPackRegistry.register` | `packs.json`; resolved at render time via `usericon@iconsetpath = "<uid>/<file>"` |

`IconsetPackParser` refuses a pack whose `uid` is not a single safe path segment and drops icons whose `filename` is absolute or contains `..`; the importer and `IconPackRegistry.remove` also check canonical-path containment. `ProfileQrCodec.gunzip` is the one importer that caps inflated size; copy that pattern.

## Export

`LassoExporters.writeKml` / `writeMissionPackage` (`MANIFEST/manifest.xml` + `cot/<uid>.cot`, uid sanitized to `[A-Za-z0-9._-]`) -> `<externalCacheDir>/exports/lasso-<ts>.{kml,zip}` -> `FileProvider` (`${applicationId}.fileprovider`, paths limited to `exports/`) -> `ACTION_SEND` chooser. `LassoSelectionService` does pure point-in-polygon selection.

## Symbology

- `data/symbology/MilStdIconService.kt`: CoT type to SIDC with progressive truncation and affiliation fallback; hard-coded floor merged with `assets/cot_types.json` (108 rows of `value, sidc, label, description, category`).
- `MilStdIconCache.kt`: LRU of rasterised `assets/milstd/<sidc>.svg` (generated by `scripts/milsymbol/generate.mjs`).
- `TakIconRegistry.kt`: bundled TAK icon suite (Spot Map dots, Markers / Google badges, FEMA) resolved from `cotType` / `iconsetPath` / `argb`, stable style-image ids, Base64 data-URL cache for Cesium.
- `FemaIconCatalog.kt`, `FemaIconCache.kt`: 12 FEMA / ICS-237 kinds, CoT type `a-f-G-I-*`, `assets/fema/<category>/<kind>.svg`.
- `IconPackRegistry.kt`: imported packs at `filesDir/iconpacks/packs.json`; `remove(uid)` deletes `packDir(uid)` recursively.
- `Echelon.kt`, `Affiliation.kt`, `BattleDimension.kt`, `CoTTypeDefinition.kt`: enums and catalogue row model.

## Persistence formats

| Store | Mechanism | Keys | Schema |
|---|---|---|---|
| User prefs | DataStore `user_prefs` | see `PREFERENCES.md` | flat typed keys |
| Servers | DataStore `tak_servers` | `servers_json`, `active_server_id` | JSON array of `TAKServer` with secret fields nulled; secrets in Keystore-backed `SecureCredentialStore` |
| Profiles | DataStore `config_profiles` | `profiles_json`, `active_profile_id` | JSON array of `ConfigProfile` (servers as `ProfileServer`, `EnrollmentPointer` currently unconsumed) |
| Local markers | DataStore `local_markers` | `local_markers_json` | JSON array of `CoTEvent` with `local-` uids |
| Mesh device config | DataStore via `MeshDeviceConfigStore` | | `MeshDeviceConfig` |
| Offline regions | `files/offline-tiles/regions.json` | | `OfflineRegion` array |
| KML overlays | `filesDir/kml_overlays/overlays.json` | | `KmlVectorOverlay` array |
| Raster overlays | `filesDir/raster_overlays/rasters.json` | | `RasterOverlay` array |
| MBTiles | `filesDir/mbtiles/mbtiles.json` | | `MBTilesOverlay` array |
| Icon packs | `filesDir/iconpacks/packs.json` | | `ImportedPack{uid, name, version, dirRelative, icons[]}` |
| Certs | `filesDir/tak-certs/` | | raw `.p12` and PEM |
| Drawings, chat | in memory only | | `DrawingStore`, `ChatStore` |
| Plugin enable flags | SharedPreferences `omnitak_plugins` | `plugin_<id>_enabled` | boolean |
| Locale, onboarding | SharedPreferences `omnitak_locale`, onboarding flag | | |

All JSON stores use `Json { ignoreUnknownKeys = true }` and fall back to empty lists on decode failure.

## Stores (domain)

| File | Role |
|---|---|
| `domain/ContactStore.kt` | UID-keyed roster; `ingest` stamps `receivedAtMs` and preserves `source`; `isEndpoint` excludes `local-*` uids and `b-m-p*` types |
| `domain/ChatStore.kt` | In-memory conversations and messages behind StateFlows with CAS updates; unread increments unless `isFromSelf` |
| `domain/DrawingStore.kt` | In-memory `Drawing` list, not persisted |
| `domain/LocalMarkerStore.kt` | Persists `local-*` markers |
| `domain/DrawingHitTest.kt` | Screen-space hit testing and rigid translate for drawings |
| `data/geofence/Geofencing.kt` | Circle / polygon zones and an entry / exit / dwell state machine; no production callers yet |
| `data/military/MilitaryReports.kt` | MEDEVAC 9-line, SALUTE, SPOTREP formatters and CoT envelope |
