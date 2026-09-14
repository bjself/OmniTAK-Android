# Map rendering (MapLibre 2D engine)

Base package: `app/src/main/kotlin/soy/engindearing/omnitak/mobile/ui/`.

## The one thing to know first

GeoJSON `fill` / `line` / `circle` / `symbol` layers do not paint reliably on Adreno 610, Mali, and the emulator's SwiftShader GPU. Production rendering of contacts, drawings, range rings, and KML shapes and points therefore uses MapLibre's **Annotation API** (`Marker`, `Polyline`, `Polygon`), not GeoJSON sources. Only measurement, the graticule, and the ADS-B aircraft still use GeoJSON pushes. The GeoJSON feeders `ContactLayer.update`, `ContactSymbolLayer`, and `DrawingLayer.update` remain in the tree but have no production callers.

## Files

| File | Role |
|---|---|
| `screens/MapScreen.kt` (about 2500 lines) | The hub. Collects every store, hoists shared engine inputs (`visibleContacts`, gesture handlers) so `TacticalMap` and `CesiumMapView` behave identically. Owns tool modes (measure, drawing, range rings, lasso, mission waypoints), all sheets (marker, FEMA, military report, layers, offline, coordinate entry), the UAS HUD, the radial menu (plugin actions appended), and plugin overlays via `LocalMapEngineHandle`. Every outbound send goes through `serverManager.sendCoT` and/or `activeMeshManager.sendCoTOverMesh`. |
| `components/TacticalMap.kt` (about 1400 lines) | MapLibre `AndroidView` wrapper around the process-retained `RetainedMapView`. One-time listeners read live callbacks from `RetainedMapView.bindings`. `DisposableEffect`s for the location puck, recenter / zoom / north / terrain / pan / follow, and style swaps. `activateLocation` / `buildPuckOptions` render the self marker (MIL-STD or triangle, stale dimming). Style JSON builders and provider constants live at the bottom. |
| `components/RetainedMapView.kt` | Process-lifetime `MapView` holder plus `Bindings`, so navigating away and back does not re-create the GL surface. |
| `components/ContactMarkerRenderer.kt` | Annotation-API contact pins: viewport cull, 500-marker budget, staleness fade, marker-to-contact map for tap routing. |
| `components/DrawingShapeRenderer.kt` | Annotation polylines / polygons for operator drawings and range rings. |
| `components/KmlOverlayRenderer.kt` | `KmlOverlayEvents` bus; inserts raster / MBTiles / offline-region layers with `addLayerAbove("basemap-tiles")`; `KmlMarkerRenderer` clusters KML points. |
| `components/KmlShapeRenderer.kt` | KML lines and polygons as annotations. |
| `components/GeoJsonLayerFeeder.kt` | The single `setGeoJson(String)` push helper for the sources that still use GeoJSON. |
| `components/GridLayer.kt`, `MeasurementLayer.kt` | Graticule into `grid-src`, measurement line and vertices into `measurement-src`. |
| `components/MapProjection.kt` | 100 ms polling projection pump (`rememberScreenProjection`) so Compose canvases (UAS, mission, geofence, lasso overlays) can draw in screen space. |
| `components/ContactLayer.kt`, `ContactSymbolLayer.kt`, `DrawingLayer.kt` | Legacy GeoJSON feeders, unused in production. `ContactSymbolLayer` references a `UserPrefs.experimentalSymbolLayer` field that no longer exists. |
| `components/CesiumMapView.kt` | The alternate 3D engine, see [cesium-3d-globe.md](cesium-3d-globe.md). |

## Style and layer order

`styleJsonForProvider()` wraps a raster basemap in `buildTacticalStyle()` with sources `basemap`, `contacts-src`, `measurement-src`, `drawings-src`, `grid-src`, `aircraft-src`. The layer order is pinned by `TacticalStyleLayerOrderTest`:

```
basemap-tiles
grid-line
drawings-fill
drawings-outline
measurement-line
measurement-points
measurement-labels
contacts-circles
contacts-labels
aircraft-circle
aircraft-label
```

`injectTerrain` prepends a `terrain-dem` raster-dem source (AWS Terrarium tiles) and a top-level `terrain` block. Raster overlays are inserted directly above `basemap-tiles`. All annotation re-pushes re-run after every style load via `addOnDidFinishLoadingStyleListener`, so a basemap switch does not lose markers.

## Basemap providers

| `UserPrefs.mapProvider` | Template | Notes |
|---|---|---|
| `OSM_RASTER` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | OSM policy requires the identifying User-Agent installed by `MapTileHttp` |
| `TOPO_HINT` (default) | `https://a.tile.opentopomap.org/{z}/{x}/{y}.png` | |
| `SATELLITE_HINT` | `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | note `{y}/{x}` order |
| `WMTS_CUSTOM` | `UserPrefs.customTileUrl` | validated only for `http` prefix and `{z}{x}{y}`; cleartext is blocked by network security config |
| `TACTICAL_STYLE_DARK_MATTER` | `https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png` | default parameter of `TacticalMap`, not selectable in Settings |

## Self marker

MapLibre `LocationComponent` with custom bitmaps from `buildPuckOptions`. Position comes from `LocationProvider` (fused) or a manual fix; heading from `DeviceHeadingProvider` when the triangle style is on. Staleness dims the puck.

## Gotchas for agents

- Adding a new overlay: prefer annotations or a Compose canvas over a GeoJSON layer unless you can test on Adreno / Mali hardware.
- Any new layer must be re-added in the style-loaded listener or it disappears on basemap switch.
- Keep `TacticalStyleLayerOrderTest` in sync if you insert a layer.
- Marker taps route through `ContactMarkerRenderer`'s marker-to-contact map; do not add a second tap listener on the map.
- `TacticalMap` compares `map.style?.json` to a string on every recomposition. It is a known perf smell, not a bug.
