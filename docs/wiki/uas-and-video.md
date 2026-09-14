# UAS (drone) control, MAVLink, and video

Base package: `app/src/main/kotlin/soy/engindearing/omnitak/mobile/`.

## Files

| File | Role | Gotchas |
|---|---|---|
| `data/uas/MavlinkConnection.kt` | MAVLink 2 client on the dronefleet codec, UDP or TCP. Owns the socket, a 1 Hz GCS HEARTBEAT (sysid 255, comp 190), the read loop feeding `StateFlow<DroneState>`, `missionEvents` / `paramValues` shared flows, `sendCommand` (COMMAND_LONG), `uploadMission`, PX4 and ArduPilot mode decoding. | UDP socket is ephemeral and re-targets `udpAddress:udpPort` to whatever host last sent a packet; first HEARTBEAT from anyone sets sysid/compid. No MAVLink signing. UDP read buffer is 280 bytes, so bundled datagrams truncate. `disconnect()` resets state. |
| `data/uas/DroneState.kt` | Immutable telemetry snapshot. `isConnected()` = heartbeat under 5 s. `vehicleClass` from MAV_TYPE. Trail keeps 30 points, STATUSTEXT keeps last 8. | |
| `data/uas/WaypointMission.kt` | `Waypoint`, `MissionPhase`, `WaypointMission`, `MissionStore` (StateFlow). Altitude 0.0 means "use cruise altitude". | |
| `data/uas/CruiseAltitude.kt` | AGL/MSL frame and `toMsl(homeAltMsl)`. | |
| `data/uas/VideoSource.kt` | Sealed `None / Rtsp(url) / RawH264Udp(port) / MpegTsUdp(port)` with port validation. | |
| `data/uas/RawH264UdpPlayer.kt` | Binds UDP `0.0.0.0:port` with SO_REUSEADDR, splits Annex B NALs, parses SPS/PPS with its own bit reader, configures `MediaCodec` AVC low-latency, renders to a Surface. | Accepts datagrams from any source. Buffer can grow without bound if a start code is never followed by another (see security audit). |
| `data/uas/H264NalSplitter.kt` | Stateful Annex B start-code splitter returning NAL payloads without start codes. | `drain()` copies the whole buffer on every push. |
| `data/uas/TerrainSampler.kt` | Terrain-RGB DEM sampler on a WGS84 2x2^z grid (not web mercator), z12 with z5 fallback, LRU bitmap cache, serialized fetches. Source is a hard-coded CloudFront distribution said to be TAK Terrain. | Provenance of the host is not verifiable from the repo. |
| `domain/UASManager.kt` | One per drone. Owns `MavlinkConnection`, `MissionStore`, cruise altitude, geofence radius, failsafe param reads, video source, follow/pursue streamer, terrain-below-drone loop, battery alert synthesizer, the 1 Hz CoT PLI pump, and every vehicle command. | Geofence check exists only in `flyTo()`; follow, pursue, orbit, and mission uploads do not check it even though the class comment says they do. Mission upload runs MISSION_CLEAR_ALL first. |
| `domain/MultiUasRegistry.kt` | Registry of `UASManager` keyed `host:port/callsign`, always has an idle sentinel, exposes `active`. | |
| `domain/MissionRehearsal.kt` | Pure GO / WARN / NO_GO AGL clearance over waypoints plus 50 m leg samples. | Opt-in from the UI, not enforced. |
| `ui/screens/UASScreen.kt` | Quick Connect form (transport, host, port, callsign), video picker, failsafe sheet, geofence field, telemetry rows, Arm / Disarm / Takeoff / RTL. Defaults `10.0.2.2:14550` UDP, `14555` TCP, RTSP preset `rtsp://10.0.2.2:8555/test` (emulator host alias). | |
| `ui/components/UasVideoPip.kt` | Picture-in-picture with three renderers: RTSP via ExoPlayer (RTP over TCP forced, 8 s timeout), raw H.264 via `RawH264UdpPlayer`, MPEG-TS via ExoPlayer `UdpDataSource` + `TsExtractor`. Expanded mode has photo, record, gimbal buttons. | Stops on ON_STOP. |
| `ui/components/UasControlBar.kt` | LAND / PAUSE-RESUME / RTL / E-STOP, shown only when armed. Only E-STOP has a confirmation dialog. | |
| `ui/components/UasFailsafeSheet.kt` | Read-only interpretation of PX4 / ArduPilot failsafe params. | |
| `ui/components/WaypointEditSheet.kt` | Per-waypoint MSL altitude (0 to 2000) and speed (1 to 30 m/s), delete. | |
| `ui/components/DroneOverlay.kt` | Compose marker projected from MapLibre at about 10 Hz, class colored, heading chevron, label pill. | |
| `ui/components/Uas*Pill.kt`, `UasAlertBanner.kt`, `UasPreflightCard.kt`, `UasSituationCard.kt`, `UasLinkAndTrail.kt`, `UasAltitudeSheet.kt`, `HomePositionOverlay.kt`, `InactiveDronesOverlay.kt` | Small map chrome for the UAS feature. | |
| `data/airspace/FaaUasFmClient.kt`, `ui/components/FaaNfzOverlay.kt` | Query the FAA UAS Facility Map ArcGIS FeatureServer for a bbox of center plus or minus 0.5 degrees and color LAANC ceilings. Center is drone HOME_POSITION or the operator's own fix. Fetched only while a UAS is connected. | Sends full-precision coordinates in a GET query. Stale UA string `OmniTAK/0.21`. |
| `data/onvif/OnvifClient.kt`, `ui/screens/OnvifCameraScreen.kt` | Minimal ONVIF over cleartext HTTP SOAP 1.2 with WS-UsernameToken digest: GetServices, GetProfiles, GetStreamUri, PTZ ContinuousMove / Stop / GotoPreset. Regex scraping, no XML parser. | Cleartext only; credentials are injected into the RTSP URL; media and PTZ URLs come from the camera's GetServices response. Docstring says untested against hardware. |

## Connection lifecycle

1. `UASScreen` -> `MultiUasRegistry.connect(droneId, host, port, callsign, transport)` -> `UASManager.connect()` -> `MavlinkConnection.connect()`.
2. UDP: ephemeral socket, initial target `host:port`, then learned from inbound packets. TCP: async connect with 5 s timeout, one long-lived stream.
3. `heartbeatLoop` at 1 Hz, `readLoop` parses frames and calls `apply()` which folds each message type into `DroneState` (HEARTBEAT, GLOBAL_POSITION_INT, SYS_STATUS, BATTERY_STATUS, GPS_RAW_INT, HOME_POSITION, VFR_HUD, WIND, STATUSTEXT).
4. `UASManager` layers cruise altitude, geofence, failsafe params, terrain below drone, follow target, video source, mission store.
5. `disconnect()` cancels scopes, closes sockets, resets to `DroneState()`.

## Commands

All go through `COMMAND_LONG` to the first-seen sysid/compid (default 1/1): ARM_DISARM, NAV_TAKEOFF, NAV_RETURN_TO_LAUNCH, NAV_LAND, DO_SET_MODE (PX4 main/sub or ArduPilot custom mode), DO_PAUSE_CONTINUE, DO_REPOSITION (fly here, with geofence and 10 m terrain buffer), DO_ORBIT, DO_CHANGE_SPEED, MISSION_START, IMAGE_START_CAPTURE, VIDEO_START/STOP_CAPTURE, DO_MOUNT_CONTROL, DO_FLIGHTTERMINATION (E-STOP, confirmed).

Mission upload: fill altitude-0 waypoints with cruise MSL -> MISSION_CLEAR_ALL -> MISSION_COUNT -> serve MISSION_REQUEST_INT with MISSION_ITEM_INT (frame GLOBAL, NAV_WAYPOINT, accept radius 2 m, yaw NaN) -> MISSION_ACK. Timeouts 3 s per item, 5 s overall. Then optional DO_CHANGE_SPEED and MISSION_START. MISSION_ITEM_REACHED updates `currentSeq`. Survey generator is a 200 m by 30 m lawnmower; circle generator is 12 points at 50 m.

Follow / pursue: PX4 uses DO_SET_MODE AUTO.FOLLOW_TARGET plus 1 Hz FOLLOW_TARGET; ArduCopter uses mode 23 plus 1 Hz GLOBAL_POSITION_INT from sysid 255. Stop returns to AUTO.LOITER. Follow-me streams the operator's own GPS fix to the vehicle.

CoT federation: `cotPumpLoop` emits `CotBuilders.buildUasPliEvent` once per second while a fix exists, uid `UAS-xxxxxxxx` minted per connect, sent via `ServerManager.sendCoT` to all connected servers.

## Video pipeline

`VideoSource` chosen on `UASScreen` -> `UasVideoPip`:
- RTSP: ExoPlayer `RtspMediaSource`, TCP interleave, 8 s timeout.
- Raw H.264: `RawH264UdpPlayer` on `0.0.0.0:port` -> `H264NalSplitter` -> SPS/PPS -> `MediaCodec` -> Surface. Default port 5000.
- MPEG-TS: ExoPlayer `UdpDataSource(4096)` + `TsExtractor`, 50 ms / 250 ms buffers. Default port 5010.

## Gotchas for agents

- Treat every inbound MAVLink message as untrusted. If you touch `MavlinkConnection`, prefer pinning the peer address after connect and filtering on the first-seen sysid.
- Any new autonomous command path should go through the same geofence and terrain checks that `flyTo()` uses.
- Do not log RTSP URLs from `OnvifClient.getStreamUri`; they carry credentials.
