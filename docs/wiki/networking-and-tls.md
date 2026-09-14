# TAK Server connectivity, TLS, and credentials

Base package: `app/src/main/kotlin/soy/engindearing/omnitak/mobile/` (all paths below are relative to it).

## Files

| File | Role | Constructed / called by |
|---|---|---|
| `data/net/TakTls.kt` | Single TLS policy object. `serverTrust(server, vault)` picks pinned-CA / system / trust-all; `clientKeyManagers()` loads the `.p12` for mTLS; `configure(HttpsURLConnection)` applies both to REST; `configureUntrusted()` is the enrollment trust-all bypass. | `TAKConnection`, `TakRestApiClient`, `CSREnrollmentService` |
| `data/CaTrust.kt` | PEM chain encode/decode, `trustManagerFor(chain)`, `systemTrustManager()`. `decodePemChain` silently skips malformed blocks and may return empty (then TakTls falls back to system trust). | `TakTls` |
| `data/CertVault.kt` | File store under `filesDir/tak-certs/` for `.p12` and CA `.pem`. Every name passes `sanitize()` (path-traversal safe, 96 chars, `[^A-Za-z0-9._-]` -> `_`). Same display name overwrites. | `TakTls`, `CSREnrollmentService`, `DataPackageBootstrap` |
| `data/CSRGenerator.kt` | BouncyCastle PKCS#10 builder (RSA 2048/4096, SHA256WithRSA). Emits only caller-supplied RDNs. | `CSREnrollmentService` |
| `data/CSREnrollmentService.kt` | Quick Connect: GET `/Marti/api/tls/config` -> POST CSR to `/Marti/api/tls/signClient/v2` -> build PKCS#12 with a random 32-char passphrase -> save `.p12` + `ca-<host>-<user>.pem`. Port default 8446, Basic auth, `HttpURLConnection`. `Config.trustSelfSigned` gates trust-all and is `false` by default on every path; deep links opt in only with `trust=true`. | `EnrollServerScreen`, `ServerQrScanScreen`, `MainActivity` |
| `data/SecureCredentialStore.kt` | `CredentialStore` interface over `EncryptedSharedPreferences` file `tak_credentials` (AES256-SIV keys, AES256-GCM values, Keystore master key). Keys `pw.<serverId>`, `certpw.<serverId>`. `available=false` if Keystore init fails. | `TAKServerStore` |
| `data/TAKConnection.kt` | One TCP or TLS socket per server. `connect()` has a 15 s timeout; `readLoop()` splits frames on `</event>` or 64 KB; `send()` on `Dispatchers.IO`. `soTimeout=0`. `received` is a `SharedFlow(extraBufferCapacity=64)` fed by `tryEmit`, so frames drop if the collector lags. `protocol` is ignored; `useTLS` alone decides socket type. | `ServerManager` |
| `domain/ServerOnboarding.kt` | The single place a deep-link / QR `ImportedServerConfig` becomes a connected server, after the user confirms in `ServerImportConfirmDialog`. Enrolls if TLS + credentials, else adds cert-less. | `AppNav` confirm button |
| `data/TAKServer.kt` | `@Serializable` server model + `ConnectionProtocol`. `matchesEndpoint()` dedups on host/port/protocol. `password` / `certificatePassword` exist only in memory after the store strips them. `allowUntrustedTls` is never set true by any code path. | everywhere |
| `data/TAKServerStore.kt` | Preferences DataStore `tak_servers` (`servers_json`, `active_server_id`). `saveServers` -> `stripSecrets` (secrets to Keystore, nulls in JSON); `servers` flow -> `rehydrateSecrets`. One-shot migration of pre-0.36 plaintext blobs. If Keystore is unavailable it silently keeps secrets in the JSON. | `ServerManager` |
| `data/TakRestApiClient.kt` | Marti REST over `HttpsURLConnection` + `TakTls.configure`. Reachability, missions list/create, data-package search/upload, attach hash. Port fixed at 8443 (GAP-097 tracks per-server port). Path segments via `urlSegment()`, queries via `encodeQuery()`, JSON via `jsonEscape()`. | `MissionSyncManager` |
| `data/MapTileHttp.kt` | Installs an OkHttp client into MapLibre that adds `User-Agent: OmniTAK/<version> (+https://omnitak.engindearing.soy; Android N)`. The URL is a contact string only, never connected to. | `OmniTAKApp`, `RegionDownloader` |
| `data/TakEnums.kt` | `Team` / `MemberRole` enums mirroring Meshtastic `atak.proto` integers. Do not renumber. | mesh codecs, prefs |
| `data/CoTSource.kt` | Provenance tag (`TAK_SERVER` / `MESH` / `LOCAL`) attached at ingest, display only. | `ServerManager`, `ContactStore` |
| `domain/ServerManager.kt` | App-scoped registry and supervisor. `connections: Map<id, TAKConnection>`, per-server state and reconnect jobs, aggregate `connectionState`, `sendCoT(xml, serverId?)` (null = broadcast to every live connection). `addServer` persists and immediately `reconcileConnections()`. `hydrate()` treats the in-memory list as authoritative; `deletedIds` tombstones stop resurrection. PLI stubs at the bottom are intentional no-ops. | `OmniTAKApp` |
| `domain/ReconnectPolicy.kt` | Pure backoff 0 -> 2 s -> x2 -> cap 30 s. `shouldReconnect()` only for Disconnected/Failed and enabled. | `ServerManager` |
| `domain/TAKConnectionService.kt` | Foreground service (`exported=false`, type `dataSync|location`) that exists only to hold FGS privilege while sockets stay warm. `START_STICKY`. Location type claimed only if runtime permission granted. | `OmniTAKApp` from aggregate state |
| `domain/ConnectionState.kt` | Sealed `Disconnected / Connecting / Connected(name, useTLS) / Failed(reason)`. | |
| `domain/MissionSyncManager.kt` | Parallel Marti sync across enabled + TLS + cert servers. `refreshAll`, `uploadDataPackage` (first online wins), `createMission`, `attachPackageToMission`. Gotcha: after all candidates fail it uploads once more to the last one just to get an error string. | `OmniTAKApp`, `MissionSyncScreen` |
| `domain/DataPackageBootstrap.kt` | On launch scans `getExternalFilesDir("import")/*.zip`, extracts `server.pref` + `.p12`, adds server (auto-connects), renames zip to `.imported`. `caLocation`/`caPassword` parsed but unused, so package servers get system trust. `split(":")` breaks bracketed IPv6. | `OmniTAKApp` |
| `domain/SelfPositionBroadcaster.kt` | 30 s PPLI loop. Fix source: manual override -> live GPS -> persisted prefs -> suppress. Also throttled mesh PPLI. Self-UID `ANDROID-<uuid>` minted once by `UserPrefsStore.ensureSelfUid()`. `<takv>` block is a static string. | `OmniTAKApp` with `sendCoT = serverManager.sendCoT` |
| `domain/CotBuilders.kt` | `buildDeleteEvent`, `rebuildEvent(+dest)`, `buildUasPliEvent`. All strings escaped via `CotXml.escape`. | `OmniTAKApp`, `UASManager`, `MeshServerRelay`, `LassoExporters`, `FemaIconCatalog` |
| `data/LocationProvider.kt` | GMS fused location, `PRIORITY_HIGH_ACCURACY`, 10 s, newer-wins gate, manual-fix override, permission-checked. | `OmniTAKApp` |
| `data/SelfFixPersistence.kt` | Pure policy: stale after 30 s, persist throttle 15 s, restored fixes broadcast `ce=9999999`. | `SelfPositionBroadcaster` |
| `data/UserPrefs.kt` | Preferences DataStore `user_prefs`, about 40 fields (callsign, team, coord format, map provider, `customTileUrl`, mesh/relay toggles, `selfUid`, last self fix). See `PREFERENCES.md` at repo root for the key table and ATAK aliases. | everywhere |

## Connection lifecycle

1. **Config.** A `TAKServer` arrives from `EnrollServerScreen` / QR / deep link (CSR enrollment yields `certificateName`, random `certificatePassword`, `caCertificateName`), from `DataPackageBootstrap` (zip), or manual Add Server. `ServerManager.addServer()` dedups on endpoint, persists via `TAKServerStore.saveServers()`, then `reconcileConnections()`.
2. **Trust decision** in `TAKConnection.openTlsSocket()`: `TakTls.clientKeyManagers()` (mTLS if `.p12` + passphrase present; returns null if passphrase missing) and `TakTls.serverTrust()`: `allowUntrustedTls` (never true) -> trust-all; else CA pin present -> `CaTrust.trustManagerFor(chain)` with **hostname verification off**; else system store with `endpointIdentificationAlgorithm = "HTTPS"`. `SSLContext("TLS")` uses platform defaults.
3. **Socket.** `connect()` inside `withTimeout(15 s)`, `startHandshake()`, state -> `Connected`. Plain TCP if `useTLS=false` (raw sockets are not governed by `network_security_config.xml`).
4. **Read loop.** Char accumulation until `</event>` or 64 KB -> `tryEmit`. `ServerManager` routes each frame: `ChatXml.parse` -> `ChatStore`; else `CoTParser.parse` -> tag `CoTSource.takServer` -> `ContactStore.ingest` -> plugin CoT handlers -> mesh relay if gateway mode is on.
5. **Aggregate / FGS.** Per-server state feeds `recomputeAggregate()`. `OmniTAKApp` observes it to start/stop `TAKConnectionService` and owns `SelfPositionBroadcaster`, which fans PPLI to every connected server.
6. **Reconnect.** A per-server supervisor collects `conn.state.drop(1)`; on Disconnected/Failed while still enabled it waits `ReconnectPolicy.nextDelayMs()` and redials. `reconnectIfNeeded()` on ON_RESUME resets policies and redials immediately. `disconnect(id)` cancels the supervisor before closing the socket.

## Credential and certificate storage

| Secret | Where | Protection |
|---|---|---|
| Basic-auth password | `EncryptedSharedPreferences` `tak_credentials`, key `pw.<serverId>` | Keystore-backed AES-GCM; excluded from backup and device transfer by the `sharedpref` rule in `res/xml/data_extraction_rules.xml` |
| `.p12` passphrase | same file, key `certpw.<serverId>` | same |
| Client cert + private key | `filesDir/tak-certs/<name>.p12` | file-based encryption only; enrolled certs have a random 32-char passphrase, data-package certs keep the package's passphrase; **not** excluded from Android 12+ device-to-device transfer |
| Enrollment CA pin | `filesDir/tak-certs/ca-<host>-<user>.pem` | public material, but a poisoned pin persists |
| Server list (secrets nulled) | DataStore `tak_servers` | plaintext JSON under FBE |
| Self UID, last fix, prefs | DataStore `user_prefs` | plaintext under FBE |

## Hard-coded endpoints in this area

| Literal | Where | Purpose |
|---|---|---|
| `:8443` + `/Marti/api/...`, `/Marti/sync/missionupload` | `TakRestApiClient.kt` | Marti REST on the operator's own host |
| `:8446` + `/Marti/api/tls/config`, `/Marti/api/tls/signClient/v2` | `CSREnrollmentService.kt` | enrollment on the operator's own host |
| `127.0.0.1`, `localhost` | `network_security_config.xml` | cleartext allowed only to the in-app MBTiles server |
| `https://omnitak.engindearing.soy` | `MapTileHttp.kt` | contact URL inside the User-Agent string only |

## Gotchas for agents

- Do not add a code path that sets `allowUntrustedTls = true`; the trust-all manager is meant to be reachable only during enrollment.
- Anything that goes into CoT XML must pass `CotXml.escape`. Numbers are fine unescaped.
- `sendCoT(xml, null)` broadcasts to all connected servers. Pass a server id for unicast.
- `TAKConnection.received` drops frames under backpressure. Keep the collector fast.
- The server list in memory (`ServerManager._servers`) is authoritative over DataStore after hydration.
- `TakTlsTest.kt` locks the trust-policy contract; update it if you change `serverTrust`.
