# Security audit, 2026-09-14

Scope: every file in this repository at commit `53f554e` (branch `main`, 0.43.0 / versionCode 108), including the Gradle wrapper binary, CI workflow, scripts, assets, and the full git history. Method: five parallel read-only reviews by subsystem, each reading every source file in its area completely, plus independent verification of each high and medium finding and of the supply-chain artifacts. No code from the repository was executed.

## Verdict

**No backdoor, covert network destination, hidden exfiltration, dynamic code loading, analytics or crash-reporting SDK, device-identifier collection, or logic keyed on date, build type, device ID, or callsign was found.**

Specifically verified:
- Every outbound socket or HTTP request targets a host the operator configured (TAK server, mesh radio, drone, camera) or a public map, terrain, airspace, or ADS-B provider reachable only through a visible, user-toggled feature. The full destination list is at the bottom of this page.
- No `Class.forName`, `DexClassLoader`, `ServiceLoader`, `Runtime.exec`, or reflection-based loading in `app/` or `plugins/`. `PluginRegistry` is a static list filled at startup.
- No hidden Unicode (zero-width, bidi override) in any source file. One raw NUL byte exists in `CSREnrollmentService.kt` inside a benign buffer-wipe call; see Info below.
- No encoded blobs longer than 200 characters in source.
- Dependencies resolve only from `google()` and `mavenCentral()` with `FAIL_ON_PROJECT_REPOS`; no `buildSrc`, init scripts, or custom Gradle plugins. All coordinates are well-known libraries.
- `gradle/wrapper/gradle-wrapper.jar` is byte-identical to the official Gradle 9.4.0 wrapper jar (SHA-256 `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`, listed on gradle.org release checksums). `gradlew` and `gradlew.bat` differ from the Gradle 9.4.0 templates only in the template commit hash and a `-Dfile.encoding` flag. `distributionUrl` points at `services.gradle.org` with `validateDistributionUrl=true`.
- CI (`.github/workflows/ci.yml`) reads no secrets and only builds and tests.
- Manifest: only `MainActivity` is exported; the foreground service and FileProvider are not; `allowBackup=false`; cleartext HTTP is permitted only to loopback.
- Git history: four authors, 334 commits, no committed keystores or private keys. One committed API token (Info I1).

The findings below are ordinary defects and design risks in a codebase that otherwise takes security seriously (Keystore-backed secrets, escaped XML everywhere, SRI on CDN scripts, sanitized cert file names, parameterized SQL).

## Findings

Severity reflects impact to an operator using the app as intended. File paths are relative to `app/src/main/kotlin/soy/engindearing/omnitak/mobile/` unless noted.

### High

**H1. Zip-slip path traversal in icon-pack import; recursive delete of an attacker-chosen directory.**
`domain/IconPackImporter.kt:103-115`, `data/symbology/IconPackRegistry.kt:90,153-154`, `data/symbology/IconsetPackParser.kt:141,148`.
The destination is `File(filesDir, "iconpacks/$uid")` joined with `iconEntry.filename`, both taken verbatim from the untrusted `iconset.xml`. Neither is canonicalized or checked for `..`. A pack with `uid="../.."` and `filename="files/datastore/tak_servers.preferences_pb"` overwrites the server list with attacker bytes; `remove(uid)` then calls `deleteRecursively()` on the same traversed path. Icon packs are commonly shared team artifacts and are imported from the Settings file picker.
Fix: reject `uid` and `filename` containing path separators or `..`, or require `dest.canonicalPath.startsWith(destDir.canonicalPath + File.separator)`.

**H2. Infinite loop in the shared protobuf field skipper, triggerable by any mesh node.**
`data/MeshtasticProtoParser.kt:513-525`.
`skipField` for wire type 2 computes `minOf(lenEnd + len.toInt(), buf.size)` with no check that the result is at or past `offset`. A varint whose low 32 bits are negative (for example `FA FF FF FF 0F`) moves the cursor backwards and the enclosing loop re-reads the same tag forever. Payload `7A FA FF FF FF 0F` in a portnum 3 Position does it. Every hand-rolled parser (`parseFromRadio`, `parseNodeInfo`, `parseMeshPacket`, `TakPacketParser`, `AtakPluginParser`, `AdminMessageParser`) uses this helper. Result: the frame collector spins at 100% CPU, all later mesh frames drop, battery drains, no crash surfaces to the user. `readLengthDelimited` and `ProtoReader.skip` already have the correct guard.
Fix: `if (len.toInt() < 0 || lenEnd + len.toInt() < lenEnd) return buf.size`.

**H3. Deep link or QR adds and auto-connects a TAK server with no confirmation; the device then streams position to it.**
`MainActivity.kt:158-184,193-246`, `ui/screens/ServerQrScanScreen.kt:291-317`, `domain/ServerManager.kt:423-438`.
`MainActivity` is exported with a BROWSABLE VIEW filter for `tak`, `atak`, `omnitak`. A web link, another app, an NFC tag, or the OS camera can deliver `atak://...?host=...` and the app calls `serverManager.addServer()`, which persists the server and connects immediately. `SelfPositionBroadcaster` then sends PPLI to every connected server every 30 s. The only feedback is a toast. The profile-import path does show `ImportPreviewDialog`; the server and enrollment paths do not. This mirrors ATAK behavior, but a confirmation sheet is cheap and removes a drive-by position-leak vector.
Related: `MainActivity.kt:176,231` log the full URI at INFO, which for enrollment links includes the username and one-time token.

**H4. "Push to device" sends a primary-channel `set_channel` with no PSK field.** Needs firmware verification.
`domain/MeshtasticManager.kt:660-680`, `data/AdminMessageSerializer.kt:221-244`.
`buildSetChannel0Name` encodes `Channel{index=0, settings{name}, role=PRIMARY}`. The comment says the PSK is "left at the firmware default", but the bytes carry no PSK. Meshtastic's `set_channel` replaces the whole channel struct, and a primary channel with an empty PSK means encryption disabled (the codebase itself documents "0 bytes = no crypto" in `MeshtasticChannelCodec.kt`). If the firmware behaves that way, one tap silently strips encryption from the operator's primary channel. The single-field `set_config` builders have the same replace-semantics risk for availability (region, rebroadcast mode).
Fix: read the current channel via `get_channel_response` (already parsed, PSK currently discarded) and echo the PSK back in `set_channel`. Verify against a real radio.

**H5. MAVLink UDP link re-targets to any host that sends a packet; no sysid pinning.**
`data/uas/MavlinkConnection.kt:147,262-265,302-311`.
The UDP socket binds an ephemeral port on all interfaces. Every received datagram updates `udpAddress:udpPort`, and the first HEARTBEAT from anyone sets the target sysid/compid. MAVLink signing is not used. A LAN host that learns the port (the app sends a HEARTBEAT every second) can inject telemetry that the app federates to the TAK server as the drone's position, and can redirect all outgoing commands, including mission uploads and the Follow-Me stream carrying the operator's GPS, to itself. The comment describes a one-time port learn; the code re-targets on every change.
Fix: after connect, pin the peer address to the configured host (allow port learning only), filter `apply()` on the first-seen sysid, consider MAVLink 2 signing.

### Medium

**M1. Deep-link and QR enrollment default to trust-all TLS and pin whatever CA the peer returns.**
`data/DeepLinkImport.kt:41-45,163-165,242-244`, `data/CSREnrollmentService.kt:315-317`, `data/net/TakTls.kt:144-155`.
`ImportedServerConfig.trustSelfSigned` defaults to true unless the link says `trust=ca|system|false|0|no`. The enrollment then uses `configureUntrusted()` (no cert validation, no hostname check), sends Basic-auth credentials over that channel, and persists the returned CA chain as the permanent pin. A network MITM during a legitimate QR onboarding captures the token and plants a rogue pin. The manual `EnrollServerScreen` correctly defaults the switch off with a warning. The `TakTls` contract comment ("callers must gate this behind an explicit, default-off operator choice") is violated by these two callers.

**M2. Remote crash: `ChatXml.parse` is unguarded in the per-server receive collector.**
`data/ChatXml.kt:91-94`, `domain/ServerManager.kt:207-216,62`.
`ChatXml.parse` is the first call on every received frame and has no try/catch. `XmlPullParserException` on a malformed frame propagates out of a coroutine launched in `CoroutineScope(SupervisorJob() + Dispatchers.Default)` with no `CoroutineExceptionHandler`, which on Android terminates the process. A rogue or compromised TAK server can crash the client with one malformed `<event`. The mesh path is protected; `CoTParser.parse` wraps itself in `runCatching`.

**M3. Chat impersonation of self via spoofed sender UID.**
`data/ChatXml.kt:122-177`, `domain/ChatStore.kt:98`, `domain/MeshChatNotifier.kt:67`.
`isFromSelf` is computed from the remote-supplied `chatgrp uid0` / `link uid`. The self UID is broadcast in every PPLI, so any peer knows it. Over mesh (where `selfUid` is passed to the parser) a message with a foreign event uid but `uid0=<victim>` renders as the victim's own message, increments no unread count, and triggers no notification. Fix: set `isFromSelf` only in `markOutgoing`, never from parsed input.

**M4. Private server chats are re-broadcast to the whole mesh.**
`ui/screens/ChatScreen.kt:637-665`, `data/UserPrefs.kt:111`.
After a GeoChat is sent to a server, if a mesh radio is connected and `broadcastOverMesh` (default true), the same text is wrapped as `GeoChat.<self>.All Chat Rooms.<id>` and sent with no recipient, regardless of whether the conversation is a 1:1 DM. Fix: gate on `convo.isGroup` or route DMs through the existing mesh DM path.

**M5. Mesh-to-server relay forwards unauthenticated mesh CoT under attacker-chosen UIDs; one path splices attacker XML verbatim.**
`domain/MeshServerRelay.kt:83-90,175-185`, `data/TakPacketParser.kt:169-172`, `data/AtakPluginParser.kt:444-447`.
With the gateway on (off by default), any mesh node can move any teammate's marker on the server by reusing their UID. The only filter is self-UID / self-callsign. `AtakPluginParser.renderDetailXml` inserts the sender's `Detail.xmlDetail` string into `rawXml` without re-serialization, and the relay prefers `rawXml`, so a mesh node can inject arbitrary `<detail>` children into what reaches the server. Mesh CoT is inherently unauthenticated and this mirrors the ATAK gateway; document the trust boundary and parse-then-reserialize `xmlDetail`.

**M6. With the relay on and MeshCore selected, relayed server contacts overwrite the operator's own advert position.**
`domain/MeshCoreManager.kt:321-337`, `domain/MeshServerRelay.kt:91-98`.
`MeshCoreManager.sendCoTOverMesh` treats every non-chat event as the local node's own position (`SET_ADVERT_LATLON` + `SEND_SELF_ADVERT`). The relay calls it for every relayable server event, so MeshCore peers see other contacts' coordinates as the operator's location.

**M7. Geofence is enforced only for "fly here"; the class comment claims it covers follow, pursue, and missions.**
`domain/UASManager.kt:123-125` vs `448-460`, `298-349`, `496-531`, `727-810`.
A stated safety control does not exist for most command paths.

**M8. Vehicle commands fire on a single tap.** Only E-STOP has a confirmation dialog. Mission uploads run MISSION_CLEAR_ALL first. `ui/screens/UASScreen.kt:449-462`, `ui/components/UasControlBar.kt:81-116`, `ui/screens/MapScreen.kt:1750-1811`. A UX trade-off for a ground station; noted because of H5.

**M9. Unbounded buffer growth in the raw H.264 UDP player.**
`data/uas/H264NalSplitter.kt:40-52,86-105`, `data/uas/RawH264UdpPlayer.kt:57-63`.
The player accepts datagrams from any source on `0.0.0.0:port`. After one start code, a stream that never sends another start code grows a `ByteArrayOutputStream` without bound, and `drain()` copies it on every push. Any LAN host can OOM the app while video is enabled.

**M10. ONVIF: cleartext HTTP only, credentials injected into the RTSP URL, camera-controlled service URLs followed.**
`data/onvif/OnvifClient.kt:42-44,126-139,154-172`.
Service URLs are `http://` only. `getStreamUri` builds `rtsp://user:pass@...`, which ExoPlayer may present in Basic auth. `discoverServices` scrapes `<XAddr>` and redirects subsequent authenticated calls wherever the camera says, so a hostile camera can harvest the password. The password field has no visual masking. Nothing is logged. Bounded by the fact that the operator chose the endpoint and network security config blocks cleartext to non-loopback hosts for the SOAP calls.

**M11. In-app tile server binds all interfaces, not loopback.**
`data/MBTilesOverlay.kt:151`.
`ServerSocket(0)` listens on `0.0.0.0` while the URL template and network security config assume `127.0.0.1`. Imported offline imagery is readable by LAN peers who guess the UUID, and the unbounded `newCachedThreadPool` with no `soTimeout` allows connection exhaustion. Fix: `ServerSocket(0, 50, InetAddress.getLoopbackAddress())` and set a socket timeout.

**M12. Enrollment secret and server-config deep links logged at INFO.** `MainActivity.kt:176,231`. Fix: log host and name only.

### Low

- **L1.** Client `.p12` files and the server-list DataStore are not excluded from Android 12+ device-to-device transfer; `data_extraction_rules.xml` excludes only `sharedpref`. Enrolled certs have random passphrases; data-package certs keep the package passphrase. `res/xml/data_extraction_rules.xml`, `data/CertVault.kt:24-30`.
- **L2.** Data-package auto-import reads zips from `getExternalFilesDir("import")` with no prompt and no per-entry size cap; on Android 8 to 10 that directory is writable by other apps with storage permission. `caLocation` / `caPassword` are parsed but never applied. `domain/DataPackageBootstrap.kt:44,71,138-139`.
- **L3.** Unbounded in-memory inflation in file importers (`IconPackImporter.kt:74`, `RasterOverlay.kt:155-158,397,478,539`); `GeoTIFFParser` sizes arrays from an attacker-chosen 32-bit count. Local DoS via `OutOfMemoryError`, which `catch (Exception)` does not contain.
- **L4.** `CotXml.buildEvent` does not escape `time` / `start` / `stale`; `CotBuilders.rebuildEvent` feeds remote timestamps back in, so a crafted marker becomes markup when the victim re-shares it. `data/CotXml.kt:79-86`.
- **L5.** No parser disables DTD processing; internal-entity expansion ("billion laughs") is a memory DoS from a hostile frame or KML. Android's KXmlParser does not resolve external entities, so classic XXE does not apply. `CoTParser.kt`, `ChatXml.kt`, `IconsetPackParser.kt`, `KmlVectorOverlay.kt`, `RasterOverlay.kt`, `DataPackageBootstrap.kt`.
- **L6.** A profile QR can set `customTileUrl`, redirecting tile requests (and therefore coarse location over time) to an arbitrary HTTPS host; `ImportPreviewDialog` shows "Map: Custom" without the URL. `ConfigProfile.kt:44-45`, `ProfilesScreen.kt:675`.
- **L7.** `useTLS` inferred from `port == 8089` when a link omits `tls` / `proto`, so a link with credentials and another port yields a plaintext server. `DeepLinkImport.kt:224`.
- **L8.** Operator or drone-home position is sent at full precision to the FAA ArcGIS facility-map service while a UAS is connected; stale UA `OmniTAK/0.21`. `data/airspace/FaaUasFmClient.kt:85-101`. Terrain samples go to an unattributed CloudFront host claimed to be TAK Terrain. `data/uas/TerrainSampler.kt:159-160`.
- **L9.** Admin responses from the radio are accepted from any sender node without checking `packet.from == myNodeNum`. `domain/MeshtasticManager.kt:414-426`.
- **L10.** Chat bodies logged at INFO (`MeshtasticManager.kt:464-468`, `MeshCoreManager.kt:260,277`); PPLI lat/lon and callsign logged at DEBUG (`SelfPositionBroadcaster.kt:115,186`).
- **L11.** Multipart upload filename is not CRLF-sanitized (`TakRestApiClient.kt:401-402`); filename comes from the app's own exporter.
- **L12.** `MissionSyncManager.uploadDataPackage` re-uploads to the last server after all fail, just to obtain an error string. `MissionSyncManager.kt:110-113`.
- **L13.** `Twd97Converter.twd97ToLatLon` iterates with no cap; absurd operator input may hang the UI. `Twd97Converter.kt:160-168`.
- **L14.** Lasso bulk delete broadcasts `t-x-d-d` tombstones for other operators' UIDs; only the self UID is protected. `MapScreen.kt:1329-1361`. ATAK parity.
- **L15.** ADS-B plugin sends a 5-degree bounding box around the camera (or self fix) to OpenSky when toggled on; PRIVACY.md names "ADS-B providers" but not OpenSky. `plugins/example-adsb/.../AdsbService.kt:104-118`.
- **L16.** GeoPackage table name from the file's own `gpkg_contents` is interpolated into SQL without quote escaping; DB is read-only. `MBTilesOverlay.kt:95`.
- **L17.** `androidx.security:security-crypto:1.1.0-alpha06` protects credentials; CI actions pinned by major tag; `milsymbol ^3.0.0` floats with no lockfile (dev-time only).

### Info

- **I1. Cesium Ion token committed to history.** `app/src/main/assets/cesium_scene.html` contained a live Ion JWT (`jti 74504cc3-...`, account id 432554) from commit `f4acf9d` (2026-05-20) until `cc8e4c8` (2026-06-09). It is gone from HEAD but present in every clone's history. Treat it as compromised and revoke it on the Ion dashboard if that has not been done.
- **I2. Raw NUL byte in source.** `data/CSREnrollmentService.kt:154` uses a literal U+0000 character as the argument to `fill()` instead of the ` ` escape. `file(1)` classifies the file as data and `grep` behaves oddly on it. The surrounding code is a benign password-buffer wipe. Replace with the escape sequence so the file is reviewable by text tooling.
- **I3.** Wrapper jar (Gradle 9.4.0) is newer than `distributionUrl` (8.11.1). Not tampering; run `./gradlew wrapper` so they agree.
- **I4.** `allowUntrustedTls` on `TAKServer` is never set true by any code path; the trust-all manager is reachable only through enrollment.
- **I5.** Plain TCP CoT (`useTLS=false`) is not governed by network security config, which only covers HTTP stacks.
- **I6.** `<takv>` reports `device="AVD" version="0.1"` for every device. Cosmetic.
- **I7.** Stale comments that disagree with code (none deceptive): `ContactSymbolLayer.kt:46` references a removed pref; `RemoteIdScanner.kt:133-139` says BT5 extended advertising is on but sets `setLegacy(true)`; `MeshNode.kt`, `MeshtasticTcpClient.kt`, `MeshDeviceSettingsScreen.kt:70-74`, `MeshCoreCoTConverter.kt:10-12`, `LassoActionsSheet.kt:45-48`, `GridLayer.kt:25`, `MeasurementLayer.kt:9`.
- **I8.** Cesium page origin is `https://cesium.com` via `loadDataWithBaseURL`; only `onReady` and `onMapEvent` are exposed to JS and both are harmless. All CoT-derived strings reaching `evaluateJavascript` go through `org.json` escaping. Verified clean.
- **I9.** Clipboard is written only on the user's "Copy Coords" action and read back only to verify that write. No unsolicited clipboard reads.
- **I10.** `configBundleUrl` remote-config described in `PREFERENCES.md` has no Android implementation; the doc is iOS-only or stale.
- **I11.** Tests: `RasterImportInstrumentedTest` writes PNGs to `/sdcard/Pictures/omnitak`; `GeoTIFFParserTest` expects `/tmp/test_geotiff.tif`. Nothing from tests ships in `main`.

## Recommended fix order

1. H1 canonical-path check in `IconPackImporter` and `IconPackRegistry.remove`.
2. H2 one-line guard in `MeshtasticProtoParser.skipField`; add a unit test with `7A FA FF FF FF 0F`.
3. M2 wrap `ChatXml.parse` in `runCatching` (or add a `CoroutineExceptionHandler` to `ServerManager.scope`).
4. H3 + M1 + M12: confirmation sheet for deep-link server add and enrollment, default `trustSelfSigned=false` for links, stop logging URIs.
5. H4 echo the existing PSK in `set_channel`; verify on hardware.
6. H5 pin MAVLink peer address and sysid.
7. M3, M4, M5, M6, M11, M9 in any order.
8. I1 revoke the historical Ion token.

## Complete list of network destinations

| Destination | Where | Trigger |
|---|---|---|
| `https://<host>:8443/Marti/...`, `https://<host>:8446/Marti/api/tls/...`, TLS/TCP socket to `<host>:<port>` | `TakRestApiClient`, `CSREnrollmentService`, `TAKConnection` | user-configured TAK server |
| `tcp://<host>:4403`, BLE GATT | `MeshtasticTcpClient`, `MeshtasticBleClient`, `MeshCoreUartClient`, `GybBleClient` | user-selected radio |
| UDP/TCP `<host>:14550/14555`, UDP listen 5000/5010, RTSP URL | `MavlinkConnection`, `RawH264UdpPlayer`, `UasVideoPip` | user-entered drone |
| `http://<host>:<port>/onvif/*`, RTSP | `OnvifClient` | user-entered camera |
| `https://tile.openstreetmap.org`, `https://a.tile.opentopomap.org`, `https://server.arcgisonline.com`, `https://basemaps.cartocdn.com`, user `customTileUrl` | `TacticalMap`, `TileMath` | map visible / offline download |
| `https://s3.amazonaws.com/elevation-tiles-prod/terrarium` | `TacticalMap.injectTerrain` | 3D terrain toggle |
| `https://cesium.com/downloads/cesiumjs/releases/1.124/...`, `https://unpkg.com/milsymbol@2.2.0/...`, ion.cesium.com, Google Photorealistic via Ion | `cesium_scene.html` | 3D globe engine |
| `https://d1g8eu4d17b9ii.cloudfront.net/t3/datasets/terrain/lcll/v1` | `TerrainSampler` | UAS connected |
| `https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/.../FAA_UAS_FacilityMap_Data_V5/FeatureServer` | `FaaUasFmClient` | UAS connected |
| `https://opensky-network.org/api/states/all` | `plugins/example-adsb/AdsbService` | ADS-B plugin switch |
| `http://127.0.0.1:<port>/<id>/{z}/{x}/{y}` | `MBTilesServer` | offline overlays (binds all interfaces, see M11) |
| `https://omnitak.engindearing.soy` | `MapTileHttp` | User-Agent string only, never contacted |
| `_tak._tcp` mDNS | `TakNsdDiscovery` | Add Server screen, discovery only |
| Google Play Services fused location | `LocationProvider` | location permission |
