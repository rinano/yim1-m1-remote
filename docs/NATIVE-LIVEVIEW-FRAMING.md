# YI M1 live-view UDP framing — verified from `libframerecv.so`

Evidence source: `analysis/apk/native/libframerecv.so` (ARMv7 Thumb, symbols present:
`ThreadFunc`, `onRecvFrame`, `startRecvLib`, `stopRecvLib`, globals `currentFrameIndex`,
`currentPacketCount`, `currentPacketIndex`, `frameBufferLen`, `gFrameBufferPtr`,
`gFrameBufferLen`, `gRecvSocket`, `gCallback`).
Disassembly: `analysis/apk/native/framerecv_thumb.txt`
(`llvm-objdump -d --triple=thumbv7-none-eabi`).

The official app has two live-view paths, selected by firmware version:
`com.xiaoyi.mirrorlesscamera.common.b.e.d()` returns
`parseVersion(firmwareVersion) >= 3.2` → **native multi-packet mode**; otherwise the Java
`LiveView` fallback decodes **one datagram per frame**.

Both paths deliver the same *frame content*:
`[ 2048-byte JSON parameter block ][ JPEG bytes ]`.
The JSON block uses exactly the same keys as `GetCameraStatus` (`CameraParamBean`).

## Mode A — firmware < 3.2: one datagram per frame (Java `LiveView.a(byte[])`)

Header is RTP-like (version 0), followed by the frame content:

```
byte0:  0b00 X P CCCC     (X = extension bit, P = padding/marker bit, CCCC = CSRC count)
byte1:  M | PT (7 bits)   (PT >= 96 means "extra header present")
headerLen = 4*CCCC + 12 + X
if (P == 1 && PT >= 96):
    off6 = headerLen + 2
    headerLen = off6 + 2 + (u16be(buf[off6..off6+1]) * 4)
frame = buf[headerLen .. end]      // [2048 JSON][JPEG]
```

The Java parser accepts up to 500 000-byte datagrams; real camera datagrams are <= 64 KiB
(a UDP datagram cannot be larger). The mock server reproduces this mode.

## Mode B — firmware >= 3.2: multi-packet frames (native `ThreadFunc`)

Each UDP datagram is at most **1500 bytes** (native receive buffer `movw r1, #0x5dc` = 1500):

```
offset  size  field          encoding
0       4     frameIndex     uint32 big-endian
4       4     packetCount    uint32 big-endian  (total fragments of this frame)
8       4     packetIndex    uint32 big-endian  (0-based fragment index)
12      n-12  payload
```

Native reassembly logic (annotated from `ThreadFunc`):

* A datagram starts a new frame when `packetIndex == 0` **and** `frameIndex !=
  currentFrameIndex`; then: `currentFrameIndex = frameIndex`,
  `currentPacketCount = packetCount`, `currentPacketIndex = 0`,
  `frameBufferLen = n - 12`, buffer[0..] = payload.
* A datagram is appended when `frameIndex == currentFrameIndex` **and**
  `packetIndex == currentPacketIndex + 1`; then append payload at
  `buffer[frameBufferLen]`, `frameBufferLen += n - 12`, `currentPacketIndex++`.
  Duplicates and gaps (non-consecutive `packetIndex`) are silently dropped — a gap
  destroys the frame (there is no partial retransmission).
* When `packetIndex + 1 == packetCount` the frame is complete and the callback is invoked
  with `frameBufferLen`; the Java callback copies the assembled buffer and decodes
  JSON at `[0, 2048)` and JPEG at `[2048, frameBufferLen)`.
* `gFrameBufferLen` is the JNI-provided capacity (102400 in `LiveView.startRecvLib`);
  oversized frames are dropped.
* The native code stores the assembled payload at direct-buffer offset 4; the Java side
  compensates (`System.arraycopy(buffer, 4, copyArray, 0, len)`). The 4 skipped bytes are
  native bookkeeping, not part of the payload — a fresh Kotlin implementation assembles
  the payloads directly and therefore starts the JSON block at offset 0.

Consequences for a pure-Kotlin receiver:

1. Bind `UDP :54321`, receive with a buffer of at least 64 KiB (datagrams are <= 1500 B in
   this mode; 64 KiB also covers Mode A).
2. Choose mode from `CameraStatus.firmwareVersion` (`>= 3.2` = Mode B) and keep a
   size-based fallback: if a datagram is <= 1500 B and `packetIndex < packetCount` and
   `packetCount` is plausible (2..200), treat it as Mode B.
3. Mode B: enforce frameIndex consistency and consecutive packetIndex; on gap reset the
   frame and count a dropped frame. Cap assembled frame at 300 KiB; drop on overflow.
4. Parse `json = frame[0, 2048)`, `jpeg = frame[2048, len)`; verify JPEG SOI (`FF D8`) and
   EOI (`FF D9`) defensively, otherwise drop the frame and request a retry.
5. `StartLiveview` is the only command that turns the stream on; the official app stops
   decoding (does not necessarily tell the camera) when leaving the screen. Sending
   `StartLiveview` again is harmless.
6. Frame arrival rate in the official app: ~10 fps idle, ~7.5 fps while recording.

## Not needed

`gFrameBufferPtr` also gets a 4-byte prefix in native memory and `onRecvFrame` uses JNI
`CallVoidMethod`; neither matters for a Kotlin reimplementation.

## Confidence

* Mode B header fields and reassembly: **verified** by reading every branch of
  `ThreadFunc` in the disassembly above (offsets `0xc58`–`0xde4`).
* Both modes sharing `[2048 JSON][JPEG]` content: **verified** by the Java decode path
  (`view/LiveView.java`) which is the only Java-side consumer.
* Exact camera resolution/quality: **unknown** (bitmap is whatever JPEG the camera sends;
  mock uses 800x600).
