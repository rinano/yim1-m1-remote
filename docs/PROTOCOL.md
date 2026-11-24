# YI M1 local protocol — recovered interface specification

This document describes the camera interface recovered from the official Android app
`com.xiaoyi.mirrorlesscamera` ("YI Mirrorless" 3.1.6, `versionCode` 51, built 2019-05-17,
YI M1 / 小蚁微单), used to build the replacement app **YI M1 遥控**.
Everything here is what the *camera itself* serves locally; the vendor cloud has been shut
down and is not used by the replacement app.

Evidence and tooling: see `docs/REVERSE-ENGINEERING.md`. The primary sources are the
decompiled official app (`analysis/jadx-out/sources/`) and the native live-view library
(`analysis/apk/native/libframerecv.so`). Public community projects that independently
reached the same protocol are listed at the end.

---

## 1. Network topology

```
  Phone                                                        YI M1 camera
  ┌──────────────┐   Wi-Fi (camera access point, WPA2)   ┌────────────────────┐
  │ YI M1 遥控   │ ────────────────────────────────────▶ │ 192.168.0.10       │
  └──────┬───────┘                                       │  ├─ HTTP :80  (API)│
         │                                               │  ├─ UDP :54321     │
         └── BLE (optional, official app only) ─────────▶│     live view      │
                                                         └────────────────────┘
```

* The camera is its own Wi-Fi access point. The phone must join that network; the camera then
  answers on `http://192.168.0.10/` with no authentication.
* There is no internet access on that network. Android therefore needs the app to bind its
  sockets to the camera network (or the system must keep the camera Wi-Fi as the active
  network), otherwise requests may be routed through mobile data.
* Live view is a UDP stream from the camera to the phone on port **54321**.
* The official app additionally pairs over BLE to obtain the hotspot SSID/WPA2 password and to
  switch the camera Wi-Fi on/off. The replacement app uses the manual flow instead: join the
  camera hotspot in the system Wi-Fi settings (SSID/password are shown by the camera), then
  open the app. BLE pair service UUIDs and characteristics are documented in §9 for future
  work.
* One client at a time: while a phone is connected, the camera serves that phone.

## 2. HTTP control API

* Method: `GET` (only).
* URL: `http://192.168.0.10/?data=<url-encoded compact JSON>`
  (e.g. `?data=%7B%22command%22%3A%22GetCameraStatus%22%7D`).
* Content: a single JSON object with a `"command"` key plus command-specific keys.
* Response: HTTP 200 with a JSON envelope:

```json
{"code": 200, "data": <payload>}
```

* `code` = `200` or `20000` means success. Any other value is a camera-side error and the app
  must show the failure rather than parse `data`. Failures can arrive with HTTP status 200.
* `1506` from `GetFileList` means "no files" (the official app treats it as an empty album).
* `204` as a `GetFile` reply for a thumbnail means "thumbnail size unavailable", not a missing
  file.
* Connection timeouts used by the official app: 10 s for normal commands; shooting uses a
  longer, burst-aware timeout (20 s × (delay count + 1), min 20 s).

### 2.1 Command reference

Keys and value types are exactly as sent by the official app
(`analysis/jadx-out/sources/com/xiaoyi/mirrorlesscamera/http/camera/b.java`).

| Command | Extra JSON keys | Purpose |
|---|---|---|
| `GetCameraStatus` | – | Full camera state (see §4) |
| `CheckPreUpdate` | – | Firmware availability probe (cloud off; answer is camera-local) |
| `GetFileList` | `range_start` int, `range_end` int, `filetype` "all" | Gallery listing page (1-based, 60 per page) |
| `GetFileInfo` | `path` string | Single file metadata |
| `GetFile` | `path`, `date`, `resulotion` = `Thumbnail`/`MidThumb`/`Original` | Download raw file bytes (the misspelling `resulotion` is literal) |
| `DeleteFile` | `file_list` string[] | Delete files (protected files are refused) |
| `StartLiveview` | – | Official app has the builder but never calls it (dead code); kept for compatibility only |
| `RCStartRemoteCtl` | – | Enter remote-control session; this is what actually starts the UDP stream |
| `RCStopRemoteCtl` | – | Leave remote-control session |
| `RCGetCameraCfg` | – | Read the camera configuration |
| `RCSwitchDialMode` | `DialMode` | Exposure dial: `Auto`,`P`,`A`,`S`,`M`,`C` |
| `RCMeteringModeSet` | `MeteringMode` | `Multi`,`Spot`,`CenterWeighted` |
| `RCFocusModeSet` | `FocusMode` | `C-AF`,`S-AF`,`MF` |
| `RCImageQualitySet` | `ImageQuality` | `50`,`20`,`16`,`8`,`3`,`VGA` |
| `RCImageAspect` | `ImageAspect` | `4:3`,`3:2`,`16:9`,`1:1` |
| `RCFileFormatSet` | `FileFormat` | `JPG-S`,`JPG-M`,`JPG-L`,`RAW`,`RAWJ-S`,`RAWJ-M`,`RAWJ-L` |
| `RCDriveModeSet` | `DriveMode` | `Single`,`Continuous`,`2SDelay`,`10SDelay` |
| `RCFNSet` | `Fnumber` | Aperture wire values `1.0` … `32` |
| `RCShutterSpeedSet` | `ShutterSpeed` | see table §5 |
| `RCEVSet` | `EV` | `-5.0` … `+5.0` with 1/3 steps, e.g. `0.3` |
| `RCISOSet` | `ISO` | `Auto`, `100` … `25600` |
| `RCWBSet` | `WB` | `Auto`,`Sunny`,`Shadow`,`Cloudy`,`Incandescent`, Kelvin `2000`…`11500` |
| `RCChooseColorMode` | `ColorMode` | `Standard`,`Portrait`,`Vivid`,`NaturalBW`,`HContrastBW` |
| `RCDoFocus` | `Mode` = `Auto`\|`Manual`, `Posx`, `Posy` strings | Focus: auto-area or touch point |
| `RCDoShooting` | – | Take a photo (long timeout; cancel with `RCCancelShooting`) |
| `RCCancelShooting` | – | Cancel a running capture |
| `RCCancelShooting1` | – | Secondary cancel (60 s timeout) |
| `RCMFAdjust` | `Operation` (signed steps) | Manual-focus stepping |
| `RCDShootCntSet` | `RCDShootCntSet` | Delay/burst shot count |
| `UpdateFW` / `UpdateLenFW` | multipart `file` = `firmware.bin` | Firmware upload (out of scope) |

Wire formats not used by the official app but present in the camera/firmware vocabulary
(community findings): `VideoRecordingStart`, `VideoRecordingStop`, `GetFileInfo` variants.
The replacement app does not send undocumented commands.

## 3. Response examples

```json
{"code":200,"data":{"BatteryLevel":"87","SurplusPhotoCnts":"1234","ExposureMode":"Auto", "...":"..."}}
```

```json
{"code":200,"data":[{"path":"/DCIM/100YIM1/YI0001.JPG","date":"1750000000000",
                     "protectStatus":"false","filetype":"jpeg"}]}
```

`GetFile` replies with the raw file body (JPEG/DNG/MP4 bytes), not JSON — except the special
`{"code":204}` thumbnail-unavailable reply.

## 4. Camera status / state JSON

There are two state sources and they are not equivalent:

1. **`GetCameraStatus`** — a small health probe. Verified on firmware `3.1-cn` it returns exactly
   four string fields:
   `{"batteryLevel":"101","SurplusPhotoCnts":"1119","lenVer":"0.0","lenType":""}`
   where `batteryLevel == "101"` means **external power / charging** (not 101 %), `lenVer` is the
   lens firmware version and `lenType` the lens name (may be empty). Poll it every ~5 s for
   connection health; it is **not** the settings source.
2. **Live-view JSON state block** — shipped inside every preview frame (see §6). This is the
   **full current camera state** (26 keys) and the authoritative settings view; setters must be
   confirmed by observing the field change in the following frames, not by HTTP status.

Live-view state keys (`GetCameraStatus` does not provide these):

| Field | Values / meaning |
|---|---|
| `ExposureMode` | `Auto, P, A, S, M` (+ `Scene`/`C` for the custom dial position) |
| `MeteringMode` | `Multi, Spot, CenterWeighted` |
| `FocusMode` | `C-AF, S-AF, MF` |
| `ImageQuality` | `50,20,16,8,3,VGA` |
| `ImageAspect` | `4:3,3:2,16:9,1:1` (known to echo unreliably) |
| `FileFormat` | `RAW, JPG-S/M/L, RAWJ-S/M/L` |
| `DriveMode` | `Single, Continuous, 2SDelay, 10SDelay` |
| `Fnumber`, `FnumberMin`, `FnumberMax` | current aperture and the lens range |
| `ShutterSpeed` | value form, e.g. `1/30s`, `1s`, `BULB` |
| `EV` | `-5.0 … 5.0` |
| `ISOSetting` / `ISOAutoValue` | selected ISO / ISO the auto mode chose |
| `WB` | `Auto, Sunny, Shadow, Cloudy, Incandescent, <Kelvin>` |
| `ColorMode` | `Standard, Portrait, Vivid, NaturalBW, HContrastBW` |
| `LensStatus` | `0` no lens, `1` auto, `2` manual, `3` unknown/error |
| `BatteryLevel` | percent, `101` = external power |
| `FocusSupport` | capability flag (exact semantics unknown) |
| `SurplusPhotoCnts` | remaining shots |
| `VideoFormat`, `VASwitch`, `VAVol`, `VANR`, `VideoEis` | optional video-mode keys (ignore) |

The camera has **no RTC**; `date` values are SD-card clock times. Timestamps shown by the app
are the camera's, not the phone's.

## 5. Setting wire values


All enumerations come from `common/CameraSettingParams.java`; the camera accepts exactly
these strings. Full label lists live in `yim1-remote/.../data/model/CameraSettings.kt`
(`CameraOptions`).

**Exposure mode capabilities** (which controls are adjustable per mode):

| Mode | Aperture | Shutter | ISO | EV |
|---|---|---|---|---|
| `Auto` | – | – | – | ✓ |
| `P` | ✓ | ✓ | ✓ | ✓ |
| `A` | ✓ | – | ✓ | ✓ |
| `S` | – | ✓ | ✓ | ✓ |
| `M` | ✓ | ✓ | – | ✓ |
| `C` | ✓ | ✓ | ✓ | ✓ |

**Shutter speed wire values** (`RCShutterSpeedSet` takes the *display/value* form, exactly what the
live-view state reports): `TIME`, `BULB`, `60s`, `50s`, `40s`, `30s`, `25s`, `20s`, `15s`, `13s`,
`10s`, `8s`, `6s`, `5s`, `4s`, `3.2s`, `2.5s`, `2s`, `1.6s`, `1.3s`, `1s`, `1/1.3s`, `1/1.6s`,
`1/2s`, `1/2.5s`, `1/3s` … `1/4000s` (see `yim1-remote/.../model/CameraSettings.kt` for the full
list). Do not send the enum-name form (`"125"`, `"2\""`).

**Aperture wire values**: `1.0`, `1.2`, `1.4`, `1.7`, `1.8`, `2.0`, `2.2`, `2.5`, `2.8`,
`3.2`, `3.5`, `4.0`, `4.5`, `5.0`, `5.6`, `6.3`, `7.1`, `8.0`, `9.0`, `10`, `11`, `13`,
`14`, `16`, `18`, `20`, `22`, `25`, `29`, `32`.

**White balance wire values**: `Auto`, `Sunny`, `Shadow`, `Cloudy`, `Incandescent`, and
Kelvin values `2000`–`11500` in documented steps (e.g. `2500`, `3000`, `4000`, `5000`,
`6000`, `8000`, `10000`; every 50 K below 3000, every 100 K to 4000, etc. — the camera
accepts the listed values only).

## 6. Live view (UDP :54321)

1. Bind UDP 54321 **before** asking the camera to stream. The camera sends frames to that port.
2. Start: hardware-tested clients send `{"command":"RCStartRemoteCtl"}` and the stream starts. The
   official app has a `StartLiveview` builder but DEX analysis shows **no call site** (dead code);
   the replacement app sends `RCStartRemoteCtl` first and also `StartLiveview` as a best-effort
   fallback, tolerating failure of either.
3. Stop: `RCStopRemoteCtl`, then close the socket.
4. **Two wire formats exist, selected by camera firmware.** Both deliver a complete JPEG plus a
   plaintext JSON state block; packet loss costs a frame, never corrupts one.

**Format A — legacy / whole frame per datagram** (verified on firmware 3.1-cn with 40 captured
datagrams, ~19.6–20 KB each, ~30 fps, 800×600 JPEG):

```
0    4   u32be frameIndex        (+1 per datagram)
4    4   u32be timestamp         (+3003 per frame ⇒ ~90 kHz clock, 29.97 fps)
8    4   u32be constant 0x79CE4283
12   224 binary parameter table  (undecoded; ignore)
236  ... JSON state region       (NUL-padded JSON, 490 bytes in the capture)
     ... JPEG                    (SOI FF D8 FF at 2284 in the capture)
```

The official app computes the JSON offset from the header (RTP-style extension at bytes 12–15:
`jsonOffset = 16 + u16be(data[14..15]) * 4` ⇒ 236). A robust client should validate that offset,
fall back to "first `{` after byte 12", brace-match the JSON, then find the JPEG by scanning for
`FF D8 FF`. The 224-byte table is not needed.

**Format B — fragmented "small packets"** (firmware ≥ 3.2, verified from the native
`libframerecv.so` and public hardware tests):

```
per datagram: 0 u32be frameIndex | 4 u32be totalPackets | 8 u32be packetIndex | 12 payload
reassembled : 0..2048 JSON state (NUL padded) | 2048.. JPEG
```

Reassemble chunks by `packetIndex` (tolerate reordering, cap `totalPackets` ≤ 1024, drop a frame
if a newer one completes first). Datagrams are ~1.4 KB, ~30–40 per frame.

**Auto-detect** (first datagram, words w0/w1/w2): if `0 < w1 <= 1024 && w2 < w1` → Format B;
else if `w2 == 0x79CE4283` or `w1 > 1024` → Format A. Secondary confirmation by JPEG marker
position (A: 2284, B: 2048 of reassembled frame).

Practical notes: receive buffer should be ≥ 64 KiB (Format A datagrams arrive as one IP-reassembled
UDP datagram); request a large `SO_RCVBUF` (~1 MB); decode JPEG off the UI thread and keep only
the newest frame; update status from the JSON block every few frames (it changes at human speed).
Frame rate drops to ~7.5 fps while the camera records. Latency of ~1–2 s is inherent.

Touch-to-focus coordinates: the camera wants pixel positions in its preview coordinate system.
Official mapping (aspect-dependent):
* 4:3 / 1:1 → `Posx = round(nx*640 + 40)`, `Posy = round(ny*640)`
* 3:2 / 16:9 → `Posx = round(nx*720)`, `Posy = round(ny*720 - 30)`
`Mode` is `"Auto"` (center/auto; Posx/Posy `"0"`) or `"Manual"` for a tapped point.

## 7. Files: listing, download, delete

* Listing request: `{"command":"GetFileList","range_start":<int>,"range_end":<int>,"filetype":"all"}`
  with a **1-based, non-zero range** (the official app pages 60 per request: `page*60+1` …
  `(page+1)*60`; a `0..0` request fails with `code 1502`).
* Listing response (`code 200`): `data` is an array of
  `{"path":"/DCIM/100YICAM/YI000001.JPG","date":"1789400001","filetype":"picture","protectStatus":false}`:
  * `date` — Unix **seconds** as a string (camera clock; no RTC, may be wrong);
  * `filetype` — `picture` | `video` | `raw` | `rawJpeg` | `panorama`;
  * `protectStatus` — JSON boolean (protected files cannot be deleted);
  * a RAW+JPEG shot is a single `rawJpeg` entry (the `.JPG` path; the `.DNG` sibling is derived).
* End of album: a short page, or `code 1506` ("no picture anymore"). Treat as empty/end, not a
  transport error. `code 1502` is a bad-parameter/empty-request error.
* Download: `{"command":"GetFile","path":"<path>","date":"<date>","resulotion":"<Thumbnail|MidThumb|Original>"}`
  — note the firmware's `resulotion` misspelling. The response body is the **raw file bytes**.
  `204` means "this rendition cannot be produced" (typically a RAW thumbnail) and `404` also
  occurs for some RAW renditions; handle as "no preview" rather than a fatal error. Video ignores
  the rendition and streams the whole file. Camera path-length limits are tight (~50 chars for
  `GetFile`, ~52 for `DeleteFile`).
* Delete: `{"command":"DeleteFile","file_list":["<path>", ...]}`; protected files are refused.
* Saving: the replacement app publishes downloads through MediaStore
  (`Pictures/YI M1 Remote/`, `IS_PENDING` 1→0, MIME from the file extension) — no legacy storage permission.

## 8. Removed features (none of these are in the replacement app)

The official app's account/cloud/editorial features depended on servers that are gone:
`/login`, `/camera/bind`, `/camera/list`, `/myPic/*`, `/guide/*` (**摄影学院**),
`*MasterLearn*` (**大师随行**), community/discover, cloud model-sharing, social sharing
(WeChat/Weibo/QQ/Facebook), push (MiPush), feedback/FAQ, analytics. The replacement app keeps
only what works locally: connect, live view, remote capture, camera settings, gallery,
download and delete.

## 9. BLE pairing (documented; not used by the replacement app)

The official app also exposes a BLE control channel
(`common/d.java`, "BleManager"). Service and characteristics:

| UUID (base `25ad-477b-a884-5038b6de4649`) | Role |
|---|---|
| `41106dd9` | service |
| `41106da0` | pairing request |
| `41106da1` | get token (read) |
| `41106da2` | get version (read → `protocol,firmware[,model,lens]`) |
| `41106da3` | ? (read) |
| `41106da4` | start session (write `protocol,refId,CRC32(1‖refId‖token)`) |
| `41106da5` | camera Wi-Fi switch (write `ON`/`OFF`) |
| `41106da6` | get access point (read → `ssid,password`) |
| `41106da7` | token notification |
| `41106da9` | remove pairing |
| `41106dac` | sync time (write epoch seconds, ASCII) |
| `41106dae`, `41106daf` | camera-initiated notifications (`ac,3/7/8/9`…) |
| `00002902-0000-1000-8000-00805f9b34fb` | client-characteristic-config descriptor |

Read values are comma-separated ASCII; SSID/password arrive as `ssid,password`. Pairing
invalidates a previously paired phone. This is the one piece that would remove the manual
"join camera Wi-Fi" step; it is intentionally deferred because the Wi-Fi path already gives a
complete camera experience.

## 10. Cross-checks with public community work

Independent reverse-engineering projects reached the same local API (good confirmation of
the observations above; they do not contain official source):

* `github.com/bullbin/xiaoyi_m1_re_liveview` — Python, BLE pairing + HTTP command table +
  UDP live-view decoder.
* `github.com/echeetah/yi-m1-monitor` — Python/Swift, deep protocol notes.
* `github.com/xiaobaiwud12/yi-m1-controller-app` — Android, verified against real hardware;
  `docs/PROTOCOL.md`.

The camera-control endpoint itself (`192.168.0.10`, `?data=` JSON) is validated by all three.
Any differences are flagged in `research/verified/sources.md`.

## 11. Not covered / open

* Exact camera preview JPEG resolution and encoder quality.
* Full semantics of `FocusSupport`/`LensStatus` strings beyond the values the official app
  checks (`"0"`, `"2"`, `"3"`).
* Firmware update (`UpdateFW`) — deliberately out of scope; it is safety-critical and the
  update availability check depended on the vendor cloud.
* The camera's maximum number of gallery pages; the app pages until a short page is returned.

## 12. Hardware-verified addendum (2026-11-24, 一台 YI M1 3.1-CN 真机)

Behaviour observed on a physical YI M1 while building/testing v1.0.1, including several points
that static analysis could not settle:

### 12.1 Remote-control session is single-client and exclusive

* `RCStartRemoteCtl` → `{"code":200}` starts the UDP live-view stream (this is the command that
  actually starts it on this firmware; `StartLiveview` and `RCGetCameraCfg` return **HTTP 404**).
* While the RC session is active, every **non-RC** command (`GetCameraStatus`, `GetFileList`,
  `GetFile`, …) is rejected with `{"code":1515,"data":"RC only one"}`.
* `RCStopRemoteCtl` → `{"code":200}` releases the session; subsequent non-RC commands succeed.
  A new `RCStartRemoteCtl` also takes over a stale session left by a killed client.
* Consequence (implemented): the app must send `RCStopRemoteCtl` when leaving Remote/live view or
  when backgrounding, otherwise the gallery API stays blocked. `stopLiveView` waits up to 2.5 s
  for the request gate and then sends the stop directly, so a long capture cannot skip it.

### 12.2 Live-view Format A on this firmware

* One ~30–48 KB datagram per frame, ~30 fps: `+0 frameIndex`, `+4 timestamp`, `+8 session word`,
  `+12` RTP-style extension table (`0x0001,55`), `+236` 2048-byte NUL-padded JSON, `+2284` JPEG.
* The session word is **not** the documented `0x79CE4283` on this camera but `0xC4389FF9`;
  detection must rely on header fields (frameIndex > 1024 / datagram > 1500 B), not the constant.
* JSON at 236 was confirmed by decoding the real datagrams (battery, EV, ISO, shutter, focus…).

### 12.3 Status / capture / settings round-trips

* `GetCameraStatus` on this firmware returns the short 4-field object
  `{"batteryLevel","SurplusPhotoCnts","lenVer","lenType"}`; `batteryLevel:"101"` means external
  power (the app shows 「外接电源」).
* `RCDoShooting` adds the next file immediately (`PB230129.JPG`) and updates
  `SurplusPhotoCnts` (1844 → 1843; the counter is re-estimated after camera restarts).
* `RCSwitchDialMode("S")` and `RCFileFormatSet("JPG-M")` were echoed by the live-view state
  (`1/400s`, `f/1.8`; format 「JPG 中」), i.e. the live JSON is the authoritative setting echo.
* Real file paths: `/DCIM/100YICAM/PBnnnnnn.DNG|JPG`; `date` is a decimal Unix-seconds **string**
  (e.g. `1795476926`), and `GetFile` returned the file even with a wrong `date` — send the listing
  value anyway.

### 12.4 Camera's JSON string escaping

* The camera firmware parses `\/` as a literal two-character sequence. Android's
  `org.json.JSONObject.toString()` escapes **every** `/` in a string as `\/` (the desktop org.json
  used by unit tests does not), so `GetFile` with a path built on-device answered **204**.
* Client fix: `CameraProtocol.buildUrl()` rewrites `\/` → `/` before URL-encoding; regression
  unit test added.

### 12.5 RAW renditions are fast JPEG thumbnails (corrected 2026-11-24)

Re-measured in isolation on the physical camera (the earlier "whole DNG is streamed" claim was a
wrong inference from a wedged-server symptom):

| `resulotion` on `/DCIM/100YICAM/PB230128.DNG` | Result |
|---|---|
| `Thumbnail` | HTTP 200, **8,803 B**, `image/jpeg`, **0.26 s** |
| `MidThumb` | HTTP 200, **631,084 B**, `image/jpeg`, **1.28 s** |
| `Original` | HTTP 200, 31,931,408 B (the actual raw file) |
| any other string | HTTP 404 |

So the camera serves embedded JPEG previews for DNG files just like for JPEGs, and the official app
loads its camera-gallery cells/previews exactly this way (`common/e.java`: `thumbnail` =
`GetFile(resulotion=Thumbnail)`, `previewUrl` = `MidThumb`/`Original`; for `TYPE_RAW_IMG` the
preview uses `previewUrl`). The app therefore requests **Thumbnail** for grid cells and **MidThumb**
for the full-screen preview, and only ever requests **Original** for an actual download. The earlier
server wedge was caused by request flooding/aborted transfers during development, not by RAW
renditions themselves; never stream `Original` as a preview is still the rule.

Video: no sample was available on the card during this measurement, so video renditions remain
unverified (the official code builds the same `Thumbnail` URL for videos, which suggests a poster
JPEG may exist — to be measured with a real MP4).

### 12.6 BLE provisioning verified end-to-end

* BLE scan finds the camera advertising the hotspot name (`YI_M1_XXXX`, MAC
  `REDACTED-MAC`, RSSI -55 dBm at ~1 m).
* Pairing flow: read `da2` → enable notifications `da7` → pair request `da0` ("1,key,android") →
  token on `da7` → `da4` ("1,key,crc32") → camera-screen confirmation → read `da6` (SSID / WPA2
  password) → write WiFi "ON" on `da5` → epoch seconds on `dac`.
* Hardware result: SSID `YI_M1_XXXX` and an 8-digit numeric password were read; joining the
  camera hotspot with that password (WPA2) **succeeded**, proving the read password is the real
  key. Note: writing WiFi "ON" restarts the camera's AP, so the phone drops off it and must rejoin
  the (same-SSID, new-session) hotspot before the app can connect.

### 12.7 Photo metadata (`GetFileInfo`) — hardware reply

Request: `{"command":"GetFileInfo","path":"/DCIM/100YICAM/PB230128.DNG"}`.

Verified reply (2026-11-24):

```json
{"code":200,"data":{"type":"PICRAW","size":"31932416","width":"5200","height":"3902",
 "capture_date":"1795476953","shutter_speed":"1/25s","FNumber":"2.0","WB":"Auto",
 "ISO":"1600","FocalLength":"43","Make":"YI TECHNOLOGY","focus_mode":"S-AF","model":"M1"}}
```

* `capture_date` uses the same wall-clock-as-epoch convention as `GetFileList.date`.
* `type` observed: `PICRAW`, `PICNORMAL`; videos are expected to report a movie type (not measured —
  no MP4 on the card during the session) and may add a duration field.
* A missing file answers `{"code":1000,"data":"no such file"}` (this firmware uses 1000 here, not 1506).
* The app parses this case-insensitively with aliases (`FocalLength`/`focalLength`/`focal_length`, …)
  and treats every field as optional (`CameraProtocol.parseFileInfo*`; tests pin the real reply).

### 12.8 BLE clock sync works standalone (no re-pairing required)

After the provisioning test, the app later opened a short GATT session to the remembered MAC,
discovered services and wrote the wall-clock epoch seconds to `41106dac` — the camera accepted it
(「上次对时」updated) **without** re-running the pairing handshake, as long as the camera still trusted
the phone's pairing. Automatic sync after HTTP connect was verified the same way. Turning Wi-Fi on/off
(`setWifi` on `41106da5`) and unpair (`41106da9`) are implemented but were not exercised in this batch.

## 12.9 Battery level semantics and hidden commands (v1.0.4 research)

`GetCameraStatus.batteryLevel` is an integer:

* **0–100** — battery percentage (rendered as `%` by the app and by the official APK, whose
  `CustomBatteryLoading`/`CameraStatusActivity` map 0–100 to the battery UI);
* **101** — external power / charging; the official app shows a charging icon, and this app shows
  「外接电源（充电中）」. No percentage is available in this state.

Hardware observations (3.1-CN): with USB unplugged the value changed `101 → 50`; after ~10 min of
live-view + 6 RAW captures it read `25` (stable while idle), so the gauge is coarse (likely 25 % steps)
but is still a usable percentage number.

The firmware command table (45 entries, see the XIAOYI_M1 research repo) contains commands the official
app never used: `CloseAP`, `StartMovieStream`/`StopMovieStream`/`PauseMovieStream`/`ResumeMovieStream`/
`ChangeCurrentFrame`, `VideoRecordingStart`/`VideoRecordingStop`, `RCVideoFormatSet`, `RCVASwitchSet`,
`RCVAVolSet`, `RCVANoiseReduceSet`, `RCEisSwitchSet`. On our 3.1-CN body, testing these through the normal
`GET /?data=` endpoint returned **HTTP 404** (not JSON `code` errors) for all tested names, so they are
reachable only through a different/unknown route or a modified firmware — do not assume they work.

## 12.10 Focus and BLE identity (v1.0.5)

**Focus.** Tap-to-focus uses `{"command":"RCDoFocus","Mode":"Manual","Posx":"<x>","Posy":"<y>"}` and
auto-area focus uses `Mode:"Auto"`. The camera returns `{"Posx":"<x>","Posy":"<y>"}`; in Auto mode the
plausible coordinates (e.g. `360,240` for the center) can be mapped back with the inverse of
`CameraProtocol.focusPoint` (`4:3`: `nx=(x-40)/640`, `ny=y/640`; `3:2/16:9`: `nx=x/720`,
`ny=(y+30)/720`). A successful HTTP response is the focus-finished signal (the camera holds the request
until AF completes, ~2 s in indoor light); the app therefore maps success/failure to the green/red reticle.
`LensStatus` 2/3 disables touch focus, matching the official app.

**BLE identity.** Reading characteristic `41106da2` returns ASCII
`"<protocol>,<bodyFw>,<region>,<lensFw>"` (hardware: `1,3.1-cn,M1CN,1.0`). Our app reads it with an
identity-only GATT session (connect → discover → read → disconnect, no pairing), persists body firmware,
region marker and lens firmware, and shows them on the 连接 page.

### Focus error code 1000

The camera can answer `{"code":1000,"data":{"Posx":"...","Posy":"..."}}` to `RCDoFocus` when it cannot
focus at the requested point (observed for a point near the top edge). Treat this as a focus failure
(red reticle), not as a protocol/transport error; the returned point is still the requested position.

### `RCEVSet` accepts only the camera's EV table

The camera's exposure-compensation values are a fixed list with alternating 0.3/0.4 steps
(`-5.0, -4.7, -4.3, … -0.3, 0.0, 0.3, 0.7, 1.0, … 5.0`). Sending an in-between value such as `0.6`
returns an **HTTP 404 HTML page** (not a JSON `code`). BKT must therefore snap to these exact wires
(`CameraOptions.ev`), not compute arbitrary tenths. Valid values always returned `{"code":200}` on 3.1-CN.
