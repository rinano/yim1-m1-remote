# Testing the YI M1 Remote app

## 0.1 v1.0.2 additions verified on hardware (2026-11-24 ~00:30–01:00)

| Feature | Evidence |
|---|---|
| RAW thumbnails | gallery cells load real DNG previews (~0.26 s each); screenshot `51-raw-thumbs.png` |
| RAW full preview | `54-raw-preview.png`, `55-exif-panel.png` |
| Photo-detail EXIF panel | all fields match the hardware `GetFileInfo` reply (30.5 MB, 5200x3902, f/2.0, 1/25s, ISO 1600, S-AF, Auto, 43mm, YI TECHNOLOGY, M1); screenshot `55-exif-panel.png` |
| Camera timestamps in UTC | `PB230128.DNG` shows 2026-11-23 23:35 (actual shot time) instead of 07:35 next day; `ui51.xml` |
| BLE clock sync | manual 对时 succeeded and showed 上次对时; automatic sync ran after a fresh process + connect; `52-time-sync.png`, `53-auto-time-sync.png` |
| Local album | lists downloaded JPG/DNG, count, thumbnails (`56-local-album.png`, `59-local-3items.png`) |
| Local viewer / edit / share / delete | info panel (`57-local-viewer.png`), rotate hash change (`62/64-*.png`), system share sheet (`65-share-chooser.png`), local delete confirmed (count 3→2) |

## 0. Real-hardware results (2026-11-23/24)

See [`../README.md`](../README.md#真机联调结果2026-112324已实机验证) for the full table. Short form:
connect / live view / tap-to-focus / shutter / settings (mode + format round-trip) / gallery
thumbnails / JPG preview / RAW+JPG download / delete / disconnect+reconnect / background+foreground /
BLE provisioning — **all verified working on the physical camera**, no crashes or ANRs. Three
hardware-only bugs were found and fixed (UDP bind port shadowing, Android `org.json` `\/` escaping,
RAW preview requests wedging the camera's HTTP server); details in `docs/PROTOCOL.md` §12.

The build host for this workspace had **no Android device and no working emulator** (no hardware
acceleration; the installed Windows emulator refuses pure software TCG — see
`android/EMULATOR-VALIDATION.md`). The app was nevertheless **validated end-to-end on physical
hardware** (Android 16 手机 Android 16 + YI M1, 2026-11-23/24) — see §0 and the per-feature results
below. The remaining sections are the reproducible checklist used for that session.

## 1. What was verified on the build host

| Check | How | Result |
|---|---|---|
| Protocol encoding/parsing | JVM unit tests (`gradle --offline testDebugUnitTest`) | see `yim1-remote/BUILD-STATUS.md` / task reports |
| Live-view framing (both formats) | synthetic datagram unit tests + byte-verified against real captures in `research/vendor/yi-m1-controller-app/app/testdata/liveview/` | parser matches the captured offsets (JSON at 2284-2048, JPEG at 2284) |
| HTTP client against a fake camera | unit test with an in-process JDK `HttpServer` | envelope, listing page, raw file download |
| End-to-end HTTP + UDP at the protocol level | `tools/mock-camera/mock_camera.py` exercised with a Python client for both framings | commands, listing, thumbnail, original, live frames decode to valid JPEG |
| APK builds | `yim1-remote/scripts/build-apk.ps1` (debug + release) | APK produced, debug-signed |
| Removed features | grep of the app sources/resources for 摄影学院 / 大师随行 / login / cloud endpoints | absent |

What was **not** verified here: anything that needs the real camera (real settings application,
real focus coordinates, real Wi-Fi/BLE pairing timing) or a real Android runtime (UI rendering,
BLE stack behaviour, MediaStore writes).

## 2. Testing without a camera (PC + phone, same Wi-Fi)

1. Start the mock camera on the PC:
   ```powershell
   python tools/mock-camera/mock_camera.py --framing legacy
   # or: --framing small   (firmware >= 3.2 framing)
   ```
2. Install the app on a phone that is on the same Wi-Fi as the PC.
3. In the app: top-bar settings (关于/设置) → 相机地址 → `http://<PC-LAN-IP>:8080` → 连接相机.
4. Exercise: status card, 遥控 live view (the mock streams 800×600 JPEG), shutter (adds a file to
   the mock gallery), settings changes (visible in the mock's live-view JSON), gallery grid,
   preview, download, delete.
5. `Ctrl+C` the mock server to test disconnect handling.

The mock also answers plain `curl` requests (see `tools/mock-camera/README.md`), which is a quick
way to confirm the PC firewall allows the phone to reach it.

## 3. Testing on the real YI M1

### 3.1 Preparation
* Charge the camera and insert an SD card (the camera refuses Wi-Fi without one).
* Turn Wi-Fi on. Two options:
  * **BLE pairing (recommended in the app):** camera powered on → app 连接 → BLE 配对 → the camera
    shows a confirmation prompt on its screen → accept it. The app then receives the hotspot
    SSID/password and asks the camera to switch its Wi-Fi on.
  * **Manual:** on the camera screen open the connection/settings page and enable Wi-Fi; then join
    the `YI_M1_*` hotspot in the phone's Wi-Fi settings (the password is shown/kept by the camera;
    it is random per session and can only be obtained over BLE or from the camera UI).
* The camera's Wi-Fi serves **one client at a time** and has no internet. Do not let the phone
  "switch to a better network" automatically.

### 3.2 Checklist
1. 连接: the app connects and shows battery, remaining shots, lens info. Battery shows
   「外接电源」 when the camera reports 101.
2. 遥控: live view appears within ~2 s, tap-to-focus moves the camera AF point, shutter takes a
   photo (camera screen shows the capture), long exposures can be cancelled.
3. Settings: change exposure mode / shutter / ISO / aperture / EV / WB / drive / quality / format /
   colour; each change must be visible on the camera's own screen and in the app overlay
   (the camera confirms through the live-view state block, not HTTP status).
4. 相册: grid lists the SD card newest-first (60 per page), thumbnails load, RAW files show a
   placeholder where the camera has no thumbnail, preview and download work, downloaded originals
   land in `Pictures/YI M1 Remote/` in the gallery, multi-select download/delete work, protected files cannot
   be deleted.
5. Reconnect: background the app, turn the camera off/on, reconnect — no stale state or crash.

### 3.3 Known real-camera behaviours that are NOT bugs
* Camera timestamps may be wrong (the camera has no RTC; times come from SD-card state).
* Live view slows to ~7.5 fps while recording video and has ~1–2 s latency.
* The camera rejects commands with `{"code":15xx}` even over HTTP 200; the app shows an error
  message instead of pretending success.
* A second phone/PC joining the camera hotspot will disconnect this app.
* Raw files have no thumbnail; verified on hardware: asking the camera for a RAW rendition makes it
  stream the **whole** raw file (no 204), which is why the app shows a placeholder instead.
* While the camera is in remote-control mode (live view open) it answers every non-RC command with
  `{"code":1515,"data":"RC only one"}`; the app sends `RCStopRemoteCtl` when leaving Remote, which
  the hardware test confirmed restores the gallery API.

## 4. Safety

* The app intentionally never sends `UpdateFW` / `UpdateLenFW` (no trustworthy firmware source after
  the vendor servers shut down; a wrong image can brick the camera).
* The app does not send `CloseAP` (turning the camera Wi-Fi off is left to the camera itself).
* BLE pairing replaces any pairing stored on the camera; the official app (if it still launches)
  will need re-pairing afterwards.
* The camera API has no authentication; anyone on the camera's hotspot can control it. Use the app
  on trusted networks only.

## v1.0.4 device acceptance (2026-11-24)

1. Install `YI-M1-Remote-1.0.4-release.apk`, connect, open 遥控.
2. Assist: open the round icon at the top-right; enable 峰值对焦 / 斑马纹 / 九宫格 / 水平仪 / 直方图;
   confirm overlays follow the image, tap-to-focus still works, and 停止/快门 unaffected.
3. 曝光检查: press it; wait ~5–10 s; dialog shows 平均亮度 / 高光溢出 / 暗部溢出 + advice;
   delete the test shot and confirm the camera file count returns to the previous value.
4. 间隔/BKT: run 3 shots @3 s (expect 完成 3 张 and 3 new camera files); then run BKT 3 shots @1.0 EV
   (expect 3 files with clearly different brightness, EV restored). Use an app-side count of
   `SurplusPhotoCnts` (remaining capacity) to confirm +3/-3.
5. Battery: unplug the camera USB power; home should show e.g. `50%`; plug it back and expect
   `外接电源（充电中）` (101).
6. Warm-up: leave the camera idle for a few minutes, then reconnect; status probe retries should
   connect without manual retry (observed ~2.9 s).

## v1.0.5 device acceptance (2026-11-24)

1. Install `YI-M1-Remote-1.0.5-release.apk`; open 连接: the status card must show `3.1-cn` / `M1CN` / `1.0`
   (cached after a prior read). Tap **读取机身信息** with Bluetooth on; values refresh in ~5 s and persist
   across force-stop/relaunch.
2. 遥控: tap a point in the live image; logcat `YI-M1-Focus` shows `focusAt ... lensStatus=1 focusMode=S-AF`
   and then `focusAt ok`; the reticle turns amber → green at the tapped point (~2 s).
3. Toggle 区域：自动; tap the image or press 自动对焦; logcat shows `focusAuto ok point=...` and the reticle
   appears at the returned point; toggle back to 点选 keeps point selection working.
4. Failure path: with the lens removed (or `LensStatus` 2/3) the reticle turns red and a snackbar explains.

## v1.0.6 touch acceptance (2026-11-24)

1. Install `YI-M1-Remote-1.0.6-release.apk`, connect, open 遥控 and wait until the live image is visible.
2. Tap several different points with ~3 s between taps; each tap must produce a `YI-M1-Focus: focusAt ...`
   log and an amber reticle; successful points turn green (`focusAt ok`).
3. Taps at extreme/unfocusable points may make the camera return code 1000; the app shows the red reticle
   plus `相机返回错误码 1000` — that is camera behaviour, not a dropped touch.
4. Switch 区域：自动 and tap: `focusAuto ok` and the reticle at the camera-returned point.

## v1.0.7 BKT acceptance (2026-11-24)

1. Set exposure compensation to +0.3 EV (or any valid value), open 间隔/BKT, choose 曝光包围（EV）,
   set 包围步长 to 0.3EV, start; the strip must show `间隔拍摄中 · 曝光包围（EV） 0.3EV` and finish
   `完成 3 张` with **no HTTP 404**; camera EV must be restored afterwards.
2. Repeat with 1.0EV step and a 0.0 base; all shots must be accepted (valid wires -1.0, 0.0, 1.0).
3. Deliberately unsupported EV values must never be sent: bracket values are selected from
   `CameraOptions.ev` (0.3/0.7/1.0 EV → 1/2/3 option steps).
4. Focus failure: tap an unfocusable point — only a red reticle appears; no extra snackbar/dialog.
