# Offline maps, MBTiles, and coordinate systems

Base package: `app/src/main/kotlin/soy/engindearing/omnitak/mobile/`.

## Offline tile pipeline

`OfflineMapsSheet` (viewport bbox, zoom RangeSlider, `TileMath.templateForProvider`) -> `OfflineRegionStore.download()` -> `OfflineMbtilesWriter.create(files/offline-tiles/<uuid>.mbtiles)` -> `RegionDownloader.download()`:
- `TileMath.enumerateTiles` (web-mercator XYZ, `MAX_ZOOM = 19`), skip tiles already present.
- `HttpTileFetcher.fetch` with the app User-Agent from `MapTileHttp.buildUserAgent`, 8 s timeouts, concurrency 4, drops responses carrying `X-Blocked` (OSM's blocked-placeholder marker).
- Write with Y flipped to TMS (`MbtilesRow.xyzToTms = 2^z - 1 - y`) via `TileCacheWriter`.
- `writeMetadata(region)`, register with `MBTilesServer`, persist `regions.json` (kotlinx.serialization).

Serving reuses `MBTilesDb` / `MBTilesServer` in `data/MBTilesOverlay.kt`: a tiny HTTP server on an ephemeral port answering `GET /<id>/{z}/{x}/{y}` from registered databases. Template `http://127.0.0.1:<port>/offline-<id>/{z}/{x}/{y}`; cleartext to loopback is allowed by `network_security_config.xml`. Note the socket is created with `ServerSocket(0)` and therefore binds all interfaces, not just loopback. `OfflineTilePolicy.decide()` puts cached layers on top when `networkAvailable()` is false.

The UI warns above 50,000 tiles but does not block. OSM's tile usage policy forbids bulk downloading; use OpenTopoMap or Esri for large regions or expect a blocked UA.

### MBTiles schema written

```sql
CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
CREATE TABLE metadata (name TEXT, value TEXT);
CREATE UNIQUE INDEX metadata_name ON metadata (name);
```
Metadata rows: `name`, `format` (png/jpg), `type=baselayer`, `version=1.0`, `minzoom`, `maxzoom`, `bounds=west,south,east,north`, `description`. All writes use `ContentValues`; reads use parameterized `rawQuery`.

`MBTilesOverlay.kt` also reads user-imported `.mbtiles` and GeoPackage (`gpkg_tile_matrix`) files.

## Files

| File | Role |
|---|---|
| `data/offline/RegionDownloader.kt` | enumeration, semaphore(4) fetch, `HttpTileFetcher` |
| `data/offline/OfflineMbtilesWriter.kt` | SQLite MBTiles 1.3 writer implementing `TileSink` |
| `data/offline/OfflineRegionStore.kt` | owns `files/offline-tiles/`, `regions.json`, progress and error flows, `networkAvailable()` |
| `data/offline/OfflineRegion.kt` | serializable region metadata, `OfflineTilePolicy` |
| `data/offline/TileCache.kt` | `MbtilesRow.xyzToTms`, `TileSink`, `TileCacheWriter` |
| `data/offline/TileMath.kt` | tile math, count and size estimate (22 KB average), `fillTemplate` with `{s}` rotation, `templateForProvider`, `normalizeTemplate` for `{$z}` / `${z}` forms |
| `ui/components/OfflineMapsSheet.kt` | download sheet, region list, delete, zoom to |
| `data/MBTilesOverlay.kt` | `RasterTileDb` implementations for MBTiles and GeoPackage, `MBTilesServer`, `MBTilesOverlay` model |
| `data/RasterOverlay.kt` | GeoTIFF / GeoPDF / KMZ ground-overlay import (see [parsers-and-imports.md](parsers-and-imports.md)) |

Provider templates (`TileMath.kt`): `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, `https://a.tile.opentopomap.org/{z}/{x}/{y}.png`, `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`, plus the user's `customTileUrl`. Custom templates are only checked for `startsWith("http")`; cleartext to non-loopback hosts is refused by the network security config.

## Coordinate systems

| Format | Implementation | Notes |
|---|---|---|
| Decimal lat/lon | `CoordFormatter.latLonDecimal` | `%.5f`, universal fallback |
| DMS | `CoordFormatter.toDms` | `D° MM' SS.SS" N/E` |
| MGRS | `mil.nga.mgrs.MGRS.from(Point.point(lon, lat))` in `CoordFormatter` | NGA library handles zones, polar, Norway/Svalbard |
| UTM | `mil.nga.mgrs.utm.UTM` + MGRS band letter | `"%d%s %.0fmE %.0fmN"` |
| BNG (OSGB36) | `data/Bng.kt` | Helmert (about 5 m) then Airy TM; null outside 49 to 61 N, -9 to 2 E, falls back to decimal |
| TWD97 (EPSG:3826) | `data/Twd97Converter.kt` | GRS80 TM2 zone 121, FULL7 and GRID5 modes, `parse()` inverse; the inverse iteration has no iteration cap |
| WGS84 2x2^z grid | `data/uas/TerrainSampler.kt` | TAK Terrain DEM only |
| Web-mercator XYZ / TMS | `data/offline/TileMath.kt`, `TileCache.kt` | basemap tiles |
| Zoom to Cesium height | `ui/components/CesiumCameraMath.kt` | engine switch |
| Haversine distance and bearing | `data/GeoMath.kt` | R = 6371008.8 m |
| Range and bearing, range rings | `data/rangebearing/RangeBearing.kt`, `RangeRings.kt` | pure math, unit tested |
| Elevation profile, line of sight | `data/terrain/ElevationProfile.kt` | earth curvature k = 0.13 |

`data/CoordClipboard.kt` writes a coordinate to the clipboard and reads `primaryClip` back only to verify the write landed. It is the sole clipboard access in the app.
