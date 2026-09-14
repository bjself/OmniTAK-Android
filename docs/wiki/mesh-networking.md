# Mesh networking: Meshtastic, MeshCore, Remote ID, GYB

Base package: `app/src/main/kotlin/soy/engindearing/omnitak/mobile/`.

## Data flow

```
Radio link            Transport (MeshTransport)          Parsing                              Manager                          Sinks (wired in OmniTAKApp)
Meshtastic Wi-Fi  ->  MeshtasticTcpClient.frames  -+    MeshtasticProtoParser.parseFromRadio
Meshtastic BLE    ->  MeshtasticBleClient.frames  -+->  NodeInfo / Position            ->  MeshtasticManager.upsertNode  ->  nodes  ->  MeshCoTBridge(MeshtasticCoTConverter)  ->  ContactStore.ingest
                                                        Packet portnum 72/257 (ATAK)   ->  TakPacketParser / AtakPluginParser  ->  cotSink -> self filter -> MeshCoTRouter -> ChatStore+MeshChatNotifier | ContactStore(source=MESH) + MeshServerRelay -> ServerManager.sendCoT
                                                        Packet portnum 78 (ATAK_V2)    ->  TakPacketV2Codec.decode        ->  cotSink (same)
                                                        Packet portnum 1 (TEXT)        ->  chatSink                       ->  ChatStore + notifier
                                                        Packet portnum 6 (ADMIN)       ->  AdminMessageParser             ->  adminResponseSink -> MeshDeviceConfigStore, chat titles
MeshCore BLE (NUS) -> MeshCoreUartClient.frames    ->   MeshCoreFrameCodec.decode      ->  MeshCoreManager.handleFrame    ->  nodes (MeshCoreCoTConverter) / cotSink / chatSink

Outbound: SelfPositionBroadcaster -> MeshFrameworkManager.sendCoTOverMesh -> TakPacketSerializer | TakPacketV2Codec | MeshCoreFrameCodec -> MeshWire.buildToRadio -> transport.send
          MeshServerRelay (server to mesh, off by default) -> same sendCoTOverMesh
          Admin writes from MeshDeviceSettingsScreen / MeshChannelShareScreen -> MeshtasticManager.dispatchAdmin | MeshCoreManager.applyChannel
```

Trust boundary: everything to the left of the manager column is attacker-influenced. Mesh peers control `Data.payload` on every portnum, and the default Meshtastic channel key is public. Only the self-UID / self-callsign filter and `CotXml.escape` stand between mesh bytes and the TAK server when the relay gateway is on.

## Files

| File | Role | Gotchas |
|---|---|---|
| `data/MeshTransport.kt` | Interface `{state, frames, send, disconnect}` shared by all transports. | |
| `data/MeshtasticTcpClient.kt` | Plain TCP to a radio on port 4403. Read loop resyncs on `0x94 0xC3`, reads a big-endian 16-bit length, emits each FromRadio. | Plaintext. 64-slot `tryEmit` buffer drops frames under backpressure. Header comment is stale. |
| `data/MeshtasticBleClient.kt` | Nordic `BleManager` for the Meshtastic GATT service. Scan filtered on the service UUID, 20 s connect watchdog, 500-byte write chunks, `fromNum` notify plus 1 s poll both drain `fromRadio`. Exposes `asTransport`. | Cannot implement `MeshTransport` directly because Nordic's `disconnect()` is final. |
| `data/MeshtasticProtoParser.kt` | Hand-rolled FromRadio decoder plus the shared varint / fixed / length helpers used by every other protobuf parser. | `skipField` for wire type 2 does not guard a negative `len.toInt()`, so a crafted unknown field loops forever (see security audit). `readLengthDelimited` and `ProtoReader.skip` are correct. |
| `data/MeshWire.kt` | The single protobuf writer and `buildToRadio(portnum, payload, to, channelIndex, packetId, hopLimit, wantAck, wantResponse)`. | Field 9 is hop_limit, 10 is want_ack. |
| `data/ProtoReader.kt` | Safe stateful protobuf reader used by channel codecs and `TakPacketV2Codec`. | |
| `data/MeshtasticCoTConverter.kt` | `MeshNode` to `CoTEvent` + XML with a `<__meshtastic__>` block. UIDs `MESHTASTIC-<HEX8>` and `mesh-self-<hex8>`. | Line 39 has a no-op `if (isOwnNode) "a-f-G-U-C" else "a-f-G-U-C"`. |
| `data/MeshtasticChannelCodec.kt` | `MeshChannel(name, psk, uplink, downlink)` and the `https://meshtastic.org/e/#<base64url ChannelSet>` share link codec. The URL is never fetched, only parsed. | PSK length 0 = no crypto, 1 = preset index, 16 / 32 = raw key. |
| `data/MeshChannelShare.kt` | `parse(input)` detects MeshCore first then Meshtastic; `shareURL`. | |
| `data/MeshNode.kt`, `MeshDeviceConfig.kt` | Node model; config draft enums (`MeshRole`, `MeshChannelPreset`, `MeshRegion`, `RebroadcastMode`) and `MeshDeviceConfigStore` with `applyAdminResponse` read-back merge. | Header comments claim write-to-device is not wired. It is. |
| `data/TakPacketParser.kt`, `TakPacketSerializer.kt` | Meshtastic ATAK-plugin TAKPacket v1 (portnum 72 / 257). PLI to `a-f-G-U-C`, GeoChat to `b-t-f`. Unishox2 compressed strings handled with `runCatching`. | `uid = contact.device_callsign` from the sender, so any node can claim any UID. |
| `data/TakPacketV2Codec.kt` | Portnum 78 TAKPacketV2 marker codec, uncompressed `0xFF` envelope only, 225-byte budget, drops remarks first. | uid and type preserved verbatim by design. |
| `data/Unishox2.kt` | Faithful Kotlin port of Unishox2 default preset. Decoder is bounded; may throw on output overflow, which callers catch. | |
| `data/AdminMessageParser.kt`, `AdminMessageSerializer.kt` | Meshtastic AdminMessage read (`get_owner/get_channel/get_config` responses) and write (`set_owner`, `set_config`, `set_channel`). Admin frames are addressed to the radio's own node number, never broadcast, so PSKs never go over the air. | `buildSetChannel0Name` sends `Channel{index, settings{name}, role}` with no `psk` field. `set_config` submessages are single-field. Meshtastic replaces the whole struct on set, so unspecified fields may reset (see security audit). |
| `data/MeshCoreUartClient.kt` | Raw Android GATT transport for a MeshCore companion radio over Nordic UART. Serialized single-in-flight write queue; one TX notification is one frame. Pairing PIN 123456 is displayed to the user, never entered programmatically. | |
| `data/MeshCoreFrameCodec.kt`, `MeshCoreChannelCodec.kt`, `MeshCoreCoTConverter.kt` | Fixed-offset binary companion frames, `meshcore://channel/add?name=&secret=<hex16>` links, contact to `a-f-G-U-C` CoT with UID `MESHCORE-<12 hex>` (48-bit id from a 32-byte pubkey). | v1 vs v3 message layouts differ by 3 bytes. |
| `data/discovery/TakMdns.kt`, `TakNsdDiscovery.kt` | `_tak._tcp` discovery to prefill Add Server. Nothing auto-connects or auto-trusts. | A rogue responder can suggest a plaintext server; the user still has to add it. |
| `data/gyb/GybBleClient.kt`, `GybDetectionParser.kt` | BLE client for the external gyb_detect ESP32 sensor; reassembles newline-delimited JSON (8 KiB cap) into synthetic Open Drone ID messages. | |
| `data/remoteid/*` | ASTM F3411 model, parser (BasicId and Location only, bounds-guarded), unfiltered BLE scanner reading service data `0xFFFA`, track store with 30 s purge, CoT converter (`RID-<id>` drone, `RID-OP-<id>` pilot). | Scanner sets `setLegacy(true)` while its comment says BT5 extended advertising is on, so BT5-only drones are missed. |
| `domain/MeshtasticManager.kt` | Owns TCP and BLE clients, node table, `connectTcp` / `connectBle` (sends `want_config_id`), BLE auto-reconnect to the last address, frame fan-out by portnum, `sendMeshChat`, `sendCoTOverMesh` (markers to 78, chat and PLI to 72), admin read/write helpers, `pushDeviceConfig`. | Admin responses are not checked against `packet.from == myNodeNum`. Chat text is logged at INFO. |
| `domain/MeshCoreManager.kt` | APP_START + DEVICE_QUERY + GET_BATTERY handshake, 2.5 s `GET_NEXT_MSG` poll, frame fan-out, pubkey DMs, `applyChannel`. | `sendCoTOverMesh` treats every non-chat event as the local node's own position and issues `SET_ADVERT_LATLON` + `SEND_SELF_ADVERT`. With the relay on, other contacts' positions become the operator's advert. |
| `domain/MeshFrameworkManager.kt` | Common interface both managers implement; `OmniTAKApp.activeMeshManager` picks one. | |
| `domain/MeshCoTBridge.kt`, `MeshCoTRouter.kt` | Bridge publishes changed nodes into `ContactStore` when `autoPublishMeshToTak` (default on). Router: `b-t-f` is CHAT, everything else CONTACT. | Bridge output has no `CoTSource`, so it is never relayed. |
| `domain/MeshServerRelay.kt` | Gateway mode, off by default (`relayGatewayEnabled`). MESH to server with 5 s dedup; server to mesh for `a-*`, `b-t-f`, `b-m-p-*` with 30 s per-uid throttle. Pure `relayTarget` / `admitForward` are unit tested. | Forwards attacker-chosen UIDs; `AtakPluginParser.renderDetailXml` splices the sender's `xmlDetail` verbatim into `rawXml`, which the relay prefers. |
| `domain/MeshChatNotifier.kt` | Notification channel `mesh_chat`; tap opens the conversation. | |
| `domain/GybManager.kt` | Lifecycle for the gyb sensor, gated on `gybDetectorEnabled` (default off), reconnect with backoff, shared `RemoteIdTrackStore`, `cotRemove` on purge. | |
| `ui/screens/MeshtasticScreen.kt`, `MeshDeviceSettingsScreen.kt`, `MeshChannelShareScreen.kt`, `MeshTopologyScreen.kt`, `ui/components/BleScanList.kt`, `MeshNodeDetailSheet.kt`, `GybDeviceSheet.kt` | Framework selector and connection panes, config draft editor with Read / Push, channel paste / scan / share / apply, node list with "Publish all to TAK" (bypasses the bridge toggle), shared BLE picker (the only place a BLE connection starts). | PSKs live only in screen-local state and are written to the radio only on explicit Apply. |

## Wire formats as implemented

- **Meshtastic TCP framing**: `94 C3 | len_hi len_lo | FromRadio protobuf`. BLE: one `fromRadio` read is one FromRadio.
- **ToRadio**: `packet=1 { MeshPacket { to=2 fixed32, channel=3, decoded=4 { Data { portnum=1, payload=2, want_response=5 } }, id=6 fixed32 random, hop_limit=9 (3), want_ack=10 } }`. `want_config_id` is ToRadio field 3.
- **FromRadio**: 2 packet, 3 my_info{1 my_node_num}, 4 node_info, 5 config, 7 config_complete_id, 10 channel. MeshPacket: 1 from, 2 to, 3 channel, 4 decoded{1 portnum, 2 payload}, 8 rx_time, 9 rx_snr, 10 hop_limit, 12/16 rx_rssi. NodeInfo: 1 num, 2 or 4 user{2 long_name, 3 short_name, 7 role}, 5 position, 7 snr, 9 last_heard, 10 device_metrics{1 battery}, 11 hops_away. Position: 1 latitude_i sfixed32 (x1e-7), 2 longitude_i, 3 altitude; (0,0) rejected.
- **AdminMessage**: set_owner=32, set_channel=33 {Channel{1 index, 2 settings, 3 role}}, set_config=34 {Config{1 device{1 role, 6 rebroadcast_mode}, 2 position{1 position_broadcast_secs}, 6 lora{1 use_preset, 2 modem_preset, 7 region}}}; responses get_channel_response=2, get_owner_response=4, get_config_response=6. ChannelSettings: 2 psk, 3 name, 4 id, 5 uplink, 6 downlink.
- **TAKPacket v1 (72/257)**: `{1 is_compressed, 2 contact{1 callsign, 2 device_callsign}, 3 group{1 role, 2 team}, 4 status{1 battery}, 5 pli{1 latitude_i, 2 longitude_i, 3 altitude, 4 speed, 5 course}, 6 chat{1 message, 2 to, 3 to_callsign}}`. Compressed strings are Unishox2. Fallback: TAKMessage `{1 takControl, 2 cotEvent{1 type ... 15 detail{1 xmlDetail, 2 group, 3 precisionlocation, 4 status, 5 takv, 6 contact, 7 track}}}` or raw CoT XML.
- **TAKPacketV2 (78)**: `[0xFF][{3 callsign, 6 latitude_i, 7 longitude_i, 8 altitude sint32, 14 uid, 16 stale_seconds, 23 cot_type_str, 24 remarks, 35 marker{1 kind, 3 color_argb, 4 readiness, 8 iconset}}]`. Flag bytes 0x00 / 0x01 (zstd) are rejected.
- **MeshCore companion**: commands `01 APP_START`, `02 SEND_TXT_MSG`, `07 SEND_SELF_ADVERT`, `0E SET_ADVERT_LATLON`, `0A GET_NEXT_MSG`, `14 GET_BATTERY`, `16 DEVICE_QUERY`, `20 SET_CHANNEL [index][name 32][secret 16]`, `38 GET_STATS`. Responses `05 SELF_INFO`, `0D DEVICE_INFO`, `0C BATTERY`, `18 STATS`, `80/8A/03` advert/contact, `07/10 CONTACT_MSG`, `08/11 CHANNEL_MSG`, `83 MESSAGES_WAITING`, `0A NO_MORE`. Offsets are in `MeshCoreFrameCodec.kt`.

## Meshtastic vs MeshCore

| | Meshtastic | MeshCore |
|---|---|---|
| Transports | TCP 4403 and BLE GATT | BLE Nordic UART only |
| Encoding | protobuf via `MeshWire` / `MeshtasticProtoParser` | fixed-offset binary frames |
| Identity | 32-bit node number, `MESHTASTIC-<HEX8>` | 32-byte pubkey collapsed to 48 bits, `MESHCORE-<12 hex>` |
| Inbound | NodeInfo, Position, ATAK CoT (72 / 78 / 257), text, admin | contact adverts, DMs, channel text |
| Outbound position | serialized PLI / marker CoT, foreign UIDs preserved | only the node's own advert |
| Chat | broadcast or DM by node number | pubkey DM only, channel text inbound only |
| Config | admin protobuf read / write | `SET_CHANNEL` only |
| Polling | push plus 1 s safety poll | explicit `GET_NEXT_MSG` every 2.5 s |

## Remote ID and GYB

On-phone: `RemoteIdScanner` -> `OpenDroneIdParser.parseServiceData` -> `RemoteIdTrackStore.ingest` -> (in `OmniTAKApp`) `RemoteIdToCoTConverter.toCoTs` -> `ContactStore.ingest`. External gyb sensor: `GybManager` -> `GybBleClient` -> `GybDetectionParser` -> the same track store, so Wi-Fi beacon sightings from the sensor and BLE sightings from the phone merge on one `RID-` UID. Remote ID events carry no `CoTSource`, so the relay never forwards them.

## Default-on behaviors operators should know

- `broadcastOverMesh = true`: own position goes out on the mesh whenever a radio is connected, and server chats are re-broadcast on the mesh as all-users GeoChat.
- `autoPublishMeshToTak = true`: mesh node positions appear as map contacts.
- `relayGatewayEnabled = false`: mesh to server relay is opt-in.
- BLE auto-reconnect remembers the last user-selected address.

The Meshtastic channel URL `https://meshtastic.org/e/#ChsSEAMKERgfJi00O0JJUFdeZWwaB09tbmlUQUs` that appears in unit tests decodes to a channel named "OmniTAK" with a trivially derived 16-byte PSK. It exists only in tests and must never be pasted into a real radio.
