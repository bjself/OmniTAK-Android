# UI conventions, navigation, i18n, and tests

## Composition root

`OmniTAKApp` (`app/src/main/kotlin/soy/engindearing/omnitak/mobile/OmniTAKApp.kt`) is the `Application` and the only dependency container. Screens obtain it with `LocalContext.current.applicationContext as OmniTAKApp`. Lazy singletons include `serverManager`, `contactStore`, `chatStore`, `drawingStore`, `localMarkerStore`, `userPrefsStore`, `configProfileStore`, `certVault`, `locationProvider`, `headingProvider`, `mapCameraStore`, `meshtastic`, `meshcore`, `activeMeshManager`, `meshServerRelay`, `meshtasticCoTBridge`, `meshCoreCoTBridge`, `meshDeviceConfigStore`, `meshChatNotifier`, `gybManager`, `remoteIdScanner`, `remoteIdTrackStore`, `kmlOverlayStore`, `mbtilesOverlayStore`, `rasterOverlayStore`, `offlineRegionStore`, `missionSyncManager`, `uasRegistry` / `uasManager`, `pluginHost`, `adsbPlugin`, `diagnosticsPlugin`. Cross-screen flows: `pendingProfileImport`, `pendingChatConversation`. `appScope` is `Dispatchers.Default + SupervisorJob`. `cachedPrefs` is the non-suspending prefs snapshot for lambdas.

`onCreate` order: install MapLibre UA client -> init i18n -> load CoT catalogue -> `DataPackageBootstrap` sideload -> register and activate plugins -> launch collectors (foreground service lifecycle for server and mesh links, camera seed, self-fix persistence, PLI broadcaster, Remote ID and GYB toggles).

`MainActivity` is the single activity: edge-to-edge, onboarding gate, `AppNav()`, deep-link handling for `tak://`, `atak://`, `omnitak://`, mesh chat notification taps, and on ON_RESUME a server reconnect plus a forced location fix.

## Navigation routes (`ui/navigation/AppNav.kt`)

`map` (start), `servers`, `servers/add`, `servers/enroll`, `servers/scan`, `uas`, `onvif`, `chat`, `chat?convoId={convoId}`, `mesh`, `mesh_topology`, `mesh/device-settings`, `mesh/channels`, `missionsync`, `settings`, `settings/profiles`, `about`, `plugins`, `settings/plugin/{pluginId}`. Top-level navigation uses `popUpTo(start) { saveState }`, `launchSingleTop`, `restoreState`. `AppNav` also hosts the customizable `CustomToolbar`, keep-screen-on, toolbar auto-hide on the map, `ImportPreviewDialog` for profile imports, `ToolsLauncherSheet`, `ToolbarAddPalette`, `KmlOverlaysSheet`. A debug-only (FLAG_DEBUGGABLE) block auto-imports the first `.kml/.kmz` in the import directory.

## Conventions

- The full-screen map is sacred. Editors are `ModalBottomSheet`s or `AlertDialog`s, never new screens.
- Cross-screen "do this" requests use `MutableStateFlow<Long>` generation counters: `ToolbarEditBus`, `CoordinateEntryEvents`, `LassoSelectionService.activationGeneration`, `KmlOverlayEvents`.
- Bottom toolbar is a `ToolbarCatalog` of `BarItem` (min 2, max 6), long-press to edit.
- Long-press on the map opens `RadialMenu`; entries are built in `MapScreen` with plugin actions appended.
- Every send toasts the local-vs-sent outcome. Delivery counts as success if either the server or the mesh accepted it.
- Design tokens live in `DESIGN_TOKENS.md` and `ui/theme/Color.kt`; reach for a token before inlining a hex.
- Coordinates are displayed through `rememberCoordText`, which honours the coordinate-format preference.
- Contributing rules (`CONTRIBUTING.md`): Compose for all UI, coroutines and Flow, no `!!` in production code, 4-space indent, no wildcard imports, new networking or CoT parsing needs a unit test.

## i18n

No `values-xx` resource folders. `i18n/Loc.kt` is an `object` with Compose snapshot state for the current language; `Loc.t(key)` falls back active -> EN -> key. Catalogues are `Map<String, String>` in `i18n/LocStrings.kt` for EN, ZH_HANT, PL, DE, FR, ES, UK with dotted keys mirroring iOS, about 435 entries. Choice persists in SharedPreferences `omnitak_locale`. `LocStringsTest` and `OnboardingLocCoverageTest` check key coverage.

## Tests

| Location | Kind | Run |
|---|---|---|
| `app/src/test/kotlin/...` | JVM unit tests: data parsers and codecs, domain stores and policies, i18n coverage, plugin host surface, style layer order, renderer feature builders | `./gradlew testDebugUnitTest` |
| `plugins/*/src/test` | plugin unit tests | `./gradlew :plugins:example-adsb:testDebugUnitTest` |
| `app/src/androidTest` | instrumented: `SymbologyValidationTest`, `OverlayRenderScreenshotTest`, `RasterImportInstrumentedTest` (writes PNGs to `/sdcard/Pictures/omnitak`), `ClipboardWriteInstrumentedTest`; fixtures in `androidTest/assets` | `./gradlew connectedDebugAndroidTest` on a device or emulator, not in CI |

JVM tests get real `org.json` and `kxml2` implementations from test dependencies because the mockable `android.jar` stubs return null. `GeoTIFFParserTest` expects a fixture at `/tmp/test_geotiff.tif`.

Contract-locking tests worth knowing: `TakTlsTest` (trust policy), `TacticalStyleLayerOrderTest` (layer order), `SecureCredentialSplitTest` (secrets never in JSON), `DeepLinkEnrollTest`, `TakPacketV2CodecTest`, `MeshServerRelayTest`, `UserPrefsDefaultsTest`.
