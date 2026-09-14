# Plugin SDK

Plugins are compile-time Gradle library modules linked into the app. There is no DEX loading, no `Class.forName`, no `ServiceLoader`, and no remote code. `PluginRegistry` is a static list filled only by `OmniTAKApp.loadBundledPlugins()`. This keeps the app Play Store compliant and is non-negotiable.

Plugins run in the host process with the host's permissions. There is no sandbox. The Plugins UI tells users so.

## Modules

| Module | Contents |
|---|---|
| `plugins/plugin-sdk` | `OmniTAKPlugin`, `PluginHost`, `PluginRegistry`, `PluginTypes` (`PluginRadialAction`, `PluginLatLng`, `PluginCoTEvent`), `LocalMapEngineHandle` (`compositionLocalOf<Any?>`). Depends on Compose and Material3 only. `consumer-rules.pro` keeps `soy.engindearing.omnitak.plugin.**`. |
| `plugins/example-adsb` | `AdsbPlugin` (overlay, settings row, `toggle`, `onCameraChanged`), `AdsbService` (15 s OpenSky poll of the camera bbox, plus or minus 2.5 degrees), `Aircraft`, `AircraftLayer` / `AdsbGeoJsonFeeder` (push to `aircraft-src`), `AdsbMapOverlay`, `AdsbSettingsContent`. Depends on SDK and MapLibre. Polling starts only from the Settings switch; `activate()` only registers hooks. |
| `plugins/example-diagnostics` | Radial probe, inbound CoT counter, settings readout. Off by default (`DISABLED_BY_DEFAULT` in `OmniTAKApp`). |

## Contract

```kotlin
interface OmniTAKPlugin {
    val pluginId: String          // reverse-DNS, identical on iOS
    val displayName: String
    val pluginVersion: String
    val pluginAuthor: String
    val pluginDescription: String
    fun activate(host: PluginHost)   // register hooks once
    fun deactivate()                 // stop background work
    fun settingsContent(): (@Composable () -> Unit)? = null
}

interface PluginHost {
    fun registerMapOverlay(overlay: @Composable () -> Unit)
    fun registerRadialAction(action: PluginRadialAction, onSelect: (PluginLatLng) -> Unit)
    fun registerCoTHandler(handler: (PluginCoTEvent) -> Boolean)   // post-ingest; return value is advisory
    fun registerSettingsRow(label: String, icon: ImageVector)
}
```

`PluginCoTEvent` carries `uid, lat, lon, callsign, type, rawXml` for every inbound CoT. Overlays read the live engine with `LocalMapEngineHandle.current as? MapLibreMap`.

Host side: `ui/plugin/AppPluginHost.kt` keeps `SnapshotStateList`s of overlays, radial actions, CoT handlers, and settings rows tagged by the activating plugin id; `dispatchCoT` adapts `CoTEvent` to `PluginCoTEvent`; `clearForPlugin` removes a plugin's registrations on deactivate. Enable flags live in SharedPreferences `omnitak_plugins`, key `plugin_<id>_enabled`, default true unless seeded off.

## Adding a plugin

1. Create `plugins/<name>/` as an `com.android.library` module depending only on `project(":plugins:plugin-sdk")` (plus MapLibre if it draws).
2. Add `include(":plugins:<name>")` to `settings.gradle.kts`.
3. Add `implementation(project(":plugins:<name>"))` to `app/build.gradle.kts`.
4. Instantiate it in `OmniTAKApp` and call `PluginRegistry.register(...)` inside `loadBundledPlugins()`.
5. Add its id to `DISABLED_BY_DEFAULT` if it should be opt-in.
6. No manifest entries, no repositories block (settings uses `FAIL_ON_PROJECT_REPOS`).

The longer guide with iOS parity notes is `docs/PLUGIN_AUTHORING.md`.

## Tests

`plugins/example-adsb/src/test` (recenter policy, OpenSky parser) and `plugins/example-diagnostics/src/test`. Run with `./gradlew :plugins:example-adsb:testDebugUnitTest`. Host-side contract test: `app/src/test/.../plugin/PluginHostSurfaceTest.kt` with `FakePrefs.kt`.
