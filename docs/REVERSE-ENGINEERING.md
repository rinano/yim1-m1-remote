# Reverse-engineering record — official YI M1 app ("YI Mirrorless")

This document is the evidence trail for the protocol in `docs/PROTOCOL.md`, following the
REA workflow (target → inventory → decompile → native analysis → cross-check).

## 1. Target identity

| Fact | Value |
|---|---|
| Input file | `base.apk` (23,076,298 bytes) |
| SHA-256 | `2b73f7805dfcb3bde0efbe3f8286958ba849ab614317e9c0814985ea8f927001` |
| Package | `com.xiaoyi.mirrorlesscamera` |
| Version | `versionName 3.1.6_20190517`, `versionCode 51` |
| App label | `YI Mirrorless` (小蚁微单相机) |
| SDK | minSdk 19, targetSdk 26 |
| Signing | v1 JAR signature only (1 signer, cert SHA-256 `72b13341b9cb2eb467cc5c7cda8a935981662acf6d3ea47fe8c222278ae8ca3c`); no v2/v3 |
| Runtime families | `dalvik-art` + `native` (ARMv7 only: actionshot, framerecv, gpuimage, hdr, imagepipeline, imgenhance, ink, wb) |

REA Evidence (REA MCP server, REA 6.10.1):

* opened archive target `kind=archive, format=apk`, SHA-256 above;
* `inspect_artifact` Evidence `ev_5132d1fc4b4d8e58a770c70c5e7310f8c25448d9e08c5e62c195c8a0504607d3`
  (inventory `ev_e3fc95ab3f878ac9a6abab1ffc3625d91fcf7eb93e95943cbadd84e6186bf70c`):
  5,331 central-directory entries examined, 1,754 graph nodes, integrity contradictions 0;
* `project_android_application_graph` Evidence
  `ev_1ba2b661ae81ea846a2548d66bf09a8d7c23b0b46f12bf2aca12d71d66a84206` — component hashes:
  * `classes.dex` SHA-256 `ef586a3f280a654f3b5a60ab5d51c063b07bdcabe909e39807d285c798a01286`
  * `resources.arsc` SHA-256 `aafa973c9d89fed2ea2dceaf0bb03c88ed6256072bdb7ad16b2f9c76b20f3194`
  * `lib/armeabi-v7a/libframerecv.so` SHA-256 `536b5c17db44f8263a5c646fa45af63c5559d00f2097285bea368be777ffb753`
* `inspect_apk_signature` Evidence
  `ev_65fbfa9e9519a52fcc01c6782aab7e69045c43ac9ca1c0e6523eb3f5d625ad79` — v1-only signature posture.

Note: this REA installation has no JADX MCP provider configured (`REA_JADX_MCP_JAR` unset in the
running MCP server environment), so the Android-class decompiler path
(`inspect_android_package`/`inspect_android_class`/`inspect_android_method`) was not usable
from MCP. Per REA's documented fallback, the official `jadx-headless-mcp 0.7.1` release JAR
(the REA-recommended engine artifact; SHA-256 verified
`6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`) was fetched and its bundled
`jadx-core` API was driven directly to produce a complete Java decompilation. The APK itself
was inventoried/project-graphed through REA MCP before that step.

## 2. Tooling and artifacts

| Step | Tool | Output (workspace-relative) |
|---|---|---|
| APK inventory + identity | REA MCP (`open_binary`, `inspect_artifact`, `project_android_application_graph`, `inspect_apk_signature`) | Evidence IDs above |
| Manifest / resources / UI strings | Android SDK build-tools 35.0.0 `aapt2` (`dump badging`, `dump resources`, `dump strings`, `dump xmltree`) | `analysis/apk/manifest_tree.txt`, `analysis/apk/resources_dump.txt`, `analysis/apk/resource_strings.txt` |
| Class/metadata overview | Android SDK `dexdump -f` | `analysis/apk/dexdump_full.txt` |
| Full disassembly | Android SDK `dexdump -d` | `analysis/apk/dexdump_code.txt` (2.04 M lines) |
| Full Java decompilation | JADX core (from the REA-recommended headless MCP JAR), custom `DumpJadx` driver | `analysis/jadx-out/sources/**` (complete app source, 282 app classes + libs) + `analysis/jadx-out/resources/**` |
| Native live-view framing | Android NDK r28c `llvm-readelf`/`llvm-objdump --triple=thumbv7-none-eabi` | `analysis/apk/native/framerecv_thumb.txt`, `analysis/apk/native/libframerecv.so` |
| Verification mock | Python 3 + Pillow | `tools/mock-camera/mock_camera.py` |
| Cross-check | Public community RE projects | `research/vendor/**`, `research/verified/**` |

The decompiler driver source is `tools/jadx/dump/DumpJadx.java`; it calls
`jadx.api.JadxArgs`/`JadxDecompiler.load()/save()` and writes plain Java to
`analysis/jadx-out`.

## 3. Investigation map (question → evidence)

| Question | Where the answer was found |
|---|---|
| What service does the camera expose? | `resources.arsc` strings + DEX strings: `http://192.168.0.10`, `/?data=`, UDP 54321; `dexdump_code.txt` string table |
| How are commands encoded? | `analysis/jadx-out/.../http/camera/b.java` (`CameraHttpApi`): every `LinkedHashMap` → Gson → `http://192.168.0.10/?data=` + JSON → OkHttp GET |
| How are responses shaped? | `.../http/a/{a,b,c,d}.java` (`ObjectCallback`, `JsonCallback`, `ObjectListCallback`): `{"code","data"}` with `code==200 || 20000` |
| What request IDs exist? | DEX enum `CameraRequestID` (`dexdump_code.txt` around its `<clinit>`): 31 enum constants and their ordinals |
| Which value strings does the camera accept? | `.../common/CameraSettingParams.java` (all enums) |
| Camera status fields | `.../bean/CameraParamBean.java` (22 fields), `.../common/t.java` (mapping into app state) |
| Live view start + framing | `.../view/LiveView.java` (legacy datagram parser + `framerecv` native path), `.../common/b/e.java` (`isUDPSmallPacket = fw >= 3.2`), native `libframerecv.so` `ThreadFunc` |
| Multi-packet framing details | `libframerecv.so` Thumb disassembly (see `docs/NATIVE-LIVEVIEW-FRAMING.md`) |
| Gallery listing + download | `.../common/e.java` (`GetFileList`/`GetFile` URL construction), `.../common/f.java` (download manager), `.../bean/AlbumFile.java` |
| Wi-Fi/BLE flow | `.../common/u.java` (`YiWifiManager`), `.../common/d.java` (`BleManager`, UUIDs/handshake), `.../common/WifiAdmin.java` |
| Cloud endpoints (now dead) | DEX strings: `camera-api.xiaoyi.com`, `uscamera-api.xiaoyi.com`, `/camera/bind`, `/camera/list`, `/guide/list`, `/myPic/*`, `*MasterLearn*` + `.../http/b/*.java`, `.../common/h.java`, `.../common/l.java` |
| Home-page feature set (摄影学院 / 大师随行 / account) | `resource_strings.txt` (`摄影学院` #5690, master-learn assets/activities), activities list from `manifest_tree.txt`, `.../activity/MainActivity.java` |

## 4. Cross-checks against public community work

Three independent projects reached the same local API:

* `xiaobaiwud12/yi-m1-controller-app` — Android/Flutter; `app/docs/PROTOCOL.md` is the most
  detailed public spec and was tested on real hardware (`fw 1,3.1-cn,M1CN,0.0`). It flags the
  same command set, the `resulotion` misspelling, the BLE UUID set, and the single-client AP.
* `bullbin/xiaoyi_m1_re_liveview` — Python; BLE pairing + HTTP command table + UDP decoder.
* `echeetah/yi-m1-monitor` — Python/Swift; deep protocol/firmware notes.

Where community notes differ from the APK/native evidence, the APK/native evidence wins:

| Topic | Community note | APK/native evidence (used) |
|---|---|---|
| Multi-packet header semantics | `{u32be idx, u32be len, u32be seq}` | `{frameIndex, packetCount, packetIndex}` — the middle field is the fragment **count**; verified instruction-by-instruction from `ThreadFunc` (`docs/NATIVE-LIVEVIEW-FRAMING.md`) |
| Live-view parameter block | layout unknown (listed as open question) | fixed 2048-byte JSON block before every JPEG (`LiveView.java`) |
| `GetCameraStatus` payload | possibly 4 fields only on some firmware | our parser accepts both the 4-field form (`batteryLevel`/`SurplusPhotoCnts`/`lenVer`/`lenType`) and the rich live-view form |
| `41106da1` token char | "does not exist on real hardware" | official APK declares and reads it (tolerates absence) — BLE module treats it as optional |
| Firmware version source | BLE only | confirmed: `GetCameraStatus` is not guaranteed to carry it, so the replacement app auto-detects live-view framing by datagram size instead of firmware |
| Action-camera constants (`192.168.42.1`, port 7878, `/tmp/fuse_d`, default pw `1234567890`) | — | dead leftover of the YI Action Camera stack inside `com.pd.module.Constant`; not used by YI M1 flows (no call sites in M1 activities) |

## 5. Limitations of this analysis

* The original static analysis (this report's first sections) executed neither the official app nor
  the camera; behavioural claims there are inferred from the official app's parsing code and
  corroborated by the community hardware-tested spec. **The replacement app's protocol was then
  exercised against a physical YI M1** (2026-11-23/24, see §6): several static inferences were
  confirmed and a few were corrected there.
* The APK is the 2019 app (v3.1.6); a camera with different firmware may accept a subset of
  commands or use the legacy live-view framing. The replacement app therefore handles both
  live-view framings automatically and treats unknown status fields as absent.
* Native analysis covered the two small JNI bridges in `libframerecv.so`; other native
  libraries (HDR/white balance/action-shot) are image-processing helpers not needed for the
  replacement app.
* Absolute evidence: `docs/PROTOCOL.md` is the contract the app implements;
  `testDebugUnitTest` covers encoding/parsing; the remaining risk is real-hardware behaviour
  (see `docs/TESTING.md`).

## 6. Real-hardware verification (2026-11-24)

The replacement app (v1.0.1 debug build) was driven over adb on a Android 16 手机 (Android 16) while
the phone was on the YI M1's own hotspot, with the camera operator confirming BLE pairing on the
camera screen. Full results: `docs/TESTING.md` §0; protocol details: `docs/PROTOCOL.md` §12.

What the hardware session changed in the recovered contract:

| Static inference | Hardware observation | Consequence in the app |
|---|---|---|
| `GetCameraStatus` may return the rich `CameraParamBean` JSON | Returns only `{batteryLevel, SurplusPhotoCnts, lenVer, lenType}`; `batteryLevel:"101"` = external power | short-status parsing (already defensive) confirmed; battery shown as 「外接电源」 |
| `StartLiveview` is one of the live-view starts | returns **HTTP 404** on this firmware; `RCStartRemoteCtl` is what actually starts the UDP stream | both are sent, each failure tolerated |
| live-view Format A session word is `0x79CE4283` | `0xC4389FF9` on this unit | classification uses header heuristics, not the constant |
| a RAW rendition request returns 204/404 | camera streams the whole ~30 MB DNG with HTTP 200 and blocks its single HTTP server | app never requests RAW/video renditions; download only |
| JSON is built with `org.json` and URL-encoded | Android's JSONStringer escapes `/` as `\/`; firmware treats it literally → `GetFile` 204 | `CameraProtocol.buildUrl` normalises `\/`; regression test added |
| remote-control mode / `code 1515` semantics unspecified | while an RC session is active every non-RC command returns `{"code":1515,"rc only one"}`; `RCStopRemoteCtl` releases it, a new `RCStartRemoteCtl` takes over | app releases the session on leaving Remote / backgrounding (with a direct fallback if the request gate is busy) and retries 1515 with a stale-session clear |
| BLE provisioning flow inferred from the official app's byte writes | scan → pair → token → CRC session → SSID + 8-digit WPA2 password all work; camera-screen confirmation required; writing Wi-Fi "ON" restarts the AP | BLE card end-to-end verified; user rejoins the (same-SSID) hotspot afterwards |

All other recovered facts (command envelopes, `GetFileList` paging, `GetFile` `resolotion` spelling and
`path`+`date`, `DeleteFile` `file_list`, shutter wire values like `1/125s`, `RCDoFocus` `Auto/Manual`,
both live-view framings) were consistent with real traffic.
