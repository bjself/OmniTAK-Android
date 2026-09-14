# Build, release, and CI

Everything here was verified against the files named; treat comments in build scripts as claims, not facts.

## Toolchain

| Item | Value | Source |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | `build.gradle.kts` |
| Kotlin | 2.0.21 (android, compose, serialization plugins) | `build.gradle.kts`, `app/build.gradle.kts:7` |
| Gradle distribution | 8.11.1 (`distributionUrl`) | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle wrapper jar | official Gradle 9.4.0 wrapper jar (SHA-256 `55243ef5…145c`) | verified against gradle.org release checksums |
| JDK | 17 | `app/build.gradle.kts:103-108`, CI |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | `app/build.gradle.kts:12,22-23` |
| NDK pin | 28.2.13676358 (only for stripping overlaid prebuilt `.so` files) | `app/build.gradle.kts:18` |
| Repositories | `google()`, `mavenCentral()` only, `FAIL_ON_PROJECT_REPOS` | `settings.gradle.kts` |

## Modules

```
:app                          Android application (namespace soy.engindearing.omnitak.mobile)
:plugins:plugin-sdk           Library. OmniTAKPlugin / PluginHost / PluginRegistry contract. Compose only.
:plugins:example-adsb         Library. ADS-B (OpenSky) reference plugin. Depends on SDK + MapLibre.
:plugins:example-diagnostics  Library. Radial-action + CoT-handler reference plugin. Depends on SDK.
```

Dependency direction is strictly `:app -> plugins -> (maplibre)`. Plugins never depend on `:app`. See [plugin-sdk.md](plugin-sdk.md).

`:app` also pulls resources from `../app_assets/android` via `sourceSets` (`app/build.gradle.kts:117-119`). That directory holds `colors.xml`, `strings.xml`, `themes.xml`, and the splash drawable.

## Secrets and signing (all gitignored)

| File / property | Purpose | Fallback when absent |
|---|---|---|
| `local.properties` -> `CESIUM_ION_TOKEN` | Injected as `BuildConfig.CESIUM_ION_TOKEN`, used by `CesiumMapView` for the 3D globe | empty string, globe runs tokenless |
| `keystore.properties` (or `OMNITAK_KEYSTORE*` gradle properties) | release signing | release build silently signs with the **debug** key |
| `app/src/main/jniLibs/` | ~750 MB unstripped MapLibre `.so` overlay for Play native-crash symbolication | not present in CI; release builds happen on a workstation |

The template is `keystore.properties.example`. Never commit any of these. A Cesium Ion token was committed in `cesium_scene.html` between 2026-05-20 (`f4acf9d`) and 2026-06-09 (`cc8e4c8`); it is gone from HEAD but remains in git history and should be treated as revoked. See [security-audit.md](security-audit.md).

## Release build type

`app/build.gradle.kts:72-100`:
- R8 minify + resource shrinking on.
- Keep rules in `app/proguard-rules.pro`: keeps all of `data.**` (kotlinx.serialization models), MapLibre, MAVLink codec, BouncyCastle, `@JavascriptInterface` methods, native method names.
- `optimization.keepRules.ignoreFrom("mil.nga.mgrs:mgrs-android")` because that library's consumer rules contain `-dontobfuscate` and a global keep that would disable obfuscation app-wide.
- `ndk.debugSymbolLevel = "FULL"`.

Version: `versionCode` is monotonic (108 at 0.43.0). Release commits are `chore(release): OmniTAK Android X.Y.Z (versionCode N)`.

## Commands

```bash
./gradlew assembleDebug                 # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                  # install on connected device
./gradlew testDebugUnitTest             # JVM unit tests (what CI runs)
./gradlew connectedDebugAndroidTest     # instrumented tests, needs device/emulator (symbology, raster import, clipboard)
./gradlew lintDebug
./gradlew assembleRelease               # needs keystore.properties for a real signature
```

Unit tests can call `android.util.Log` because `unitTests.isReturnDefaultValues = true`. Tests that need real `org.json` or `XmlPullParser` implementations get them from `testImplementation("org.json:json")` and `net.sf.kxml:kxml2`.

## CI

`.github/workflows/ci.yml`: single job on `ubuntu-latest`, triggers on push to `main`/`release/*` and on pull requests. Steps: checkout v4, setup-java v4 (temurin 17), gradle/actions/setup-gradle v4, `assembleDebug`, `testDebugUnitTest`, upload test reports on failure. No secrets are consumed. No release signing, no instrumented tests, no deploy.

## Dependencies of `:app` (from `app/build.gradle.kts:141-275`)

| Coordinate | Version | Why it is here |
|---|---|---|
| androidx.compose BOM | 2024.12.01 | UI |
| androidx.core / lifecycle / activity / navigation | 1.15.0 / 2.8.7 / 1.9.3 / 2.8.4 | platform |
| androidx.datastore:datastore-preferences | 1.1.1 | `UserPrefs`, server list, profiles |
| kotlinx-serialization-json | 1.7.3 | JSON persistence models |
| androidx.security:security-crypto | 1.1.0-alpha06 | EncryptedSharedPreferences for passwords/passphrases (`SecureCredentialStore`) |
| org.maplibre.gl:android-sdk | 11.8.0 | 2D map engine |
| com.squareup.okhttp3:okhttp | 4.12.0 | custom User-Agent on tile requests (`MapTileHttp`), region downloads |
| mil.nga.mgrs:mgrs-android | 2.2.3 | MGRS/UTM conversion |
| mil.nga:tiff | 3.0.0 | GeoTIFF pixel decode (`RasterOverlay`) |
| no.nordicsemi.android:ble, ble-ktx | 2.8.0 | Meshtastic BLE transport |
| com.google.android.gms:play-services-location | 21.3.0 | fused location |
| com.caverock:androidsvg-aar | 1.4 | MIL-STD-2525 SVG rasterisation |
| org.bouncycastle:bcprov/bcpkix-jdk18on | 1.78.1 | PKCS#10 CSR for cert enrollment (`CSRGenerator`) |
| com.google.zxing:core | 3.5.3 | QR encode (`ProfileQrGenerator`) |
| com.google.mlkit:barcode-scanning | 17.3.0 | on-device QR decode |
| androidx.camera:* | 1.4.0 | QR scanner preview |
| io.dronefleet.mavlink:mavlink | 1.1.11 | MAVLink 2 codec (UAS) |
| androidx.media3 exoplayer / exoplayer-rtsp / ui | 1.4.1 | drone video PIP |

Test-only: junit 4.13.2, kotlinx-coroutines-test 1.8.1, org.json 20240303, kxml2 2.3.0, androidx.test.*, uiautomator 2.3.0.

`scripts/milsymbol/` is a Node script (dependency: `milsymbol ^3.0.0`) that regenerates `app/src/main/assets/milstd/*.svg` from `cot_types.json`. It is not part of the Gradle build.
