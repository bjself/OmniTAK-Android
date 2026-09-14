# Cesium 3D globe (WebView engine)

The 2D engine is MapLibre (see [map-rendering.md](map-rendering.md)). The optional 3D engine is CesiumJS 1.124 running inside an Android WebView. Users switch engines from the map screen; `MapScreen` hands the viewport across via `CesiumCameraMath`.

## Files

| File | Role |
|---|---|
| `app/src/main/assets/cesium_scene.html` | Self-contained scene page. Loads Cesium CSS and JS and milsymbol from CDNs with SRI hashes, sets `Cesium.Ion.defaultAccessToken` from the `__CESIUM_ION_TOKEN__` placeholder, creates a Viewer with World Terrain and Google Photorealistic 3D Tiles, defines `window.OmniBridge` (native to JS) and calls `window.OmniBridgeNative` (JS to native). Hides the Cesium credit container. Sizes the canvas in pixels because `100vh` resolves to 0 after `loadDataWithBaseURL`. |
| `ui/components/CesiumMapView.kt` | Compose `AndroidView` wrapper. Creates the WebView (`javaScriptEnabled`, `domStorageEnabled`; file access and universal access left at their secure defaults), replaces the token placeholder with `BuildConfig.CESIUM_ION_TOKEN`, loads via `loadDataWithBaseURL("https://cesium.com/", ...)`, registers `OmniBridgeNative`, pushes entities and drawings on every recomposition once `ready`, drives camera via tick counters, destroys the WebView on dispose. |
| `ui/components/CesiumEntityJson.kt` | Builds the `setEntities` JSON array with `org.json` (so callsigns and uids are escaped). Resolves TAK iconset to `spot` color, `icon` data URL, or `sidc`, in ATAK precedence order. |
| `ui/components/CesiumDrawingsJson.kt` | Builds `setDrawings` JSON. Coordinates are `[lon, lat]` to match `Cartesian3.fromDegrees`. |
| `ui/components/CesiumCameraMath.kt` | Exact inverse of the page's `_zoomFromHeight`: web-mercator zoom to camera height and back. `CesiumCameraSeed.isValid` guards interpolation into JS. |

## Bridge contract

Native to JS, all on `window.OmniBridge`. Each accepts a JSON string or object and no-ops until the viewer exists.

| Function | Payload | Behavior |
|---|---|---|
| `setEntities(arr)` | `[{uid, lat, lon, hae?, callsign?, affiliation: "f"/"h"/"n"/"u", kind: "self"/"contact"/"aircraft", sidc?, spot?: "#RRGGBB", icon?: "data:image/png;base64,...", triangle?: bool, heading?: deg}]` | Upsert each, remove entities not in the list. Self uid is `"__self__"`. `hae == 0` clamps to ground. |
| `upsertEntity(e)`, `removeEntity(uid)`, `removeAll()` | as above | single-entity ops |
| `setDrawings(arr)`, `upsertDrawing(d)` | `[{uid, kind: "line"/"polygon"/"circle", coords: [[lon, lat], ...], color: "#hex", width}]` | circle = coords[0] center, coords[1] edge |
| `flyTo({lat, lon, range?, heading?, pitch?})` | range m default 5000, pitch default -30 | 1.2 s animated |
| `setCamera({lat, lon, height, heading?, pitch?})` | pitch default -90 | immediate |
| `centerOnSelf({lat, lon})` | | lookAt preserving heading, pitch, range |
| `zoomIn()`, `zoomOut()` | | height x0.5 / x2 |
| `snapToNorth(animate)`, `setNorthUpLocked(bool)` | | heading to 0; lock installs a `camera.changed` enforcer |
| `ping()` | | `"pong"` or `"loading"` |

JS to native on `window.OmniBridgeNative` (annotated `@JavascriptInterface`, kept by proguard):
- `onReady()` fired once; native then seeds camera, pushes entities and drawings, applies north lock.
- `onMapEvent(json)`: `{event: "tap", lat, lon, hae, screenX, screenY, uid|null}`, `{event: "longpress", ...}` (500 ms hold, cancelled if moved more than 8 px), `{event: "camerachanged", lat, lon, height, heading, pitch, zoom}`. Native routes tap on `__self__` to the self sheet, tap on a contact uid to `onContactTap`, longpress to the radial menu, camerachanged to `onCameraChanged`.

## Remote resources loaded by the page

| URL | Purpose | Integrity |
|---|---|---|
| `https://cesium.com/downloads/cesiumjs/releases/1.124/Build/Cesium/Widgets/widgets.css` | Cesium CSS | SRI |
| `https://cesium.com/downloads/cesiumjs/releases/1.124/Build/Cesium/Cesium.js` | Cesium engine | SRI on the entry script; Workers and Assets it loads at runtime have none |
| `https://unpkg.com/milsymbol@2.2.0/dist/milsymbol.js` | MIL-STD-2525 glyphs | SRI |
| ion.cesium.com / api.cesium.com | World Terrain, default imagery, Google Photorealistic tiles via Ion | Ion token |

The page origin is `https://cesium.com` because of `loadDataWithBaseURL`. Only `onReady` and `onMapEvent` are exposed to JS, and both just post UI events after `JSONObject` parsing with NaN guards.

## Gotchas for agents

- Any new string that reaches `evaluateJavascript` must go through `org.json` (or numeric NaN guards). Never concatenate raw callsigns into JS.
- Keep `@JavascriptInterface` methods minimal; R8 keeps them by annotation (`proguard-rules.pro`).
- The Ion token is a client-side secret by nature. Restrict it on the Ion dashboard by domain / use. Never commit it; `local.properties` holds it.
- If you bump the Cesium version, regenerate all three SRI hashes.
