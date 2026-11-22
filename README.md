![JTTY Unicode](logo.png)

# JTTY Unicode

**JTTY on Android — keyboard-to-keyboard QSOs with full Unicode text.**

JTTY is the keyboard digital mode introduced in WSJT-X 3.2: a 4-tone GFSK signal
(~127 Hz wide) carrying 1.888 s frames of structured contest exchanges or free text,
protected by a CRC and a tail-biting convolutional code, with no clock synchronisation —
transmissions start whenever you press Send.

JTTY Unicode brings it to Android and extends it with **Unicode text transmission
(UTF-8)**, so messages can use Chinese, Japanese, Korean, Cyrillic, emoji and other
non-ASCII text while remaining compatible with the native JTTY grammar. It is built on
[JTTYAF](https://github.com/patrickrb/JTTYAF), the Android JTTY client.

---

## Features

### Receive
- **Live print** — every decoded JTTY message in the passband scrolls up as it arrives,
  with time, audio frequency and S/N; your own transmissions are echoed like a teleprinter
- **Whole-passband decode** (300–2700 Hz) with signal subtraction so overlapping stations
  print too, or RX-offset-only decode to save battery
- **Unicode receive** — reassembles UTF-8 messages across frames, verifies the CRC-8,
  inserts a replacement-character gap marker for a missing frame, and flags damaged
  messages with `[checksum missing]`
- **Waterfall** with the RX/TX offset marker; tap to retune the offset
- **Background receive** — a foreground service keeps JTTY Unicode listening with the
  screen off
- Everything received and sent is appended to `jtty_all.txt` (WSJT-X `ALL.TXT` style)

### Transmit
- **Keyboard TX** — type up to **80 native characters** or **63 UTF-8 bytes** and Send.
  Native JTTY text is upper-cased and packed with the WSJT-X grammar; any other text is
  sent as **JTTY Unicode** (UTF-8, 1–16 frames). Over-long text is silently truncated on a
  code-point boundary, matching WSJT-X
- **F1–F8 macros** with `%M` (my call), `%H` (DX call), `%E` (599 + serial); defaults map
  to the one-frame JTTY atoms (`CQ <call> CQ`, `TU <call> CQ`, `<call> TU`, `AGN?` …);
  long-press to edit
- **Contest profiles** — General, RTTY Roundup (`599 001` in one frame), Field Day
- Tap any received line to pick the DX callsign; **Log QSO** writes it to the logbook and
  bumps the serial

### Radio control
- USB CAT control for the same rig set as FT8AF (Icom, Yaesu, Kenwood, Elecraft, Xiegu,
  FlexRadio, Guohe, truSDX, hamlib-driven rigs, and more), USB audio, Bluetooth, and
  network rigs
- Band picker with the preliminary JTTY frequencies, per-band TX level, tune carrier,
  ALC auto-volume and SWR protection

### Logbook
- QSOs logged with `MODE=JTTY`, ADIF export, Cloudlog / Wavelog / QRZ logbook / World
  Radio League upload, stats and award tracking

---

## JTTY Unicode protocol

Native JTTY frames are unchanged. A Unicode message uses the extension bit (bit 33) and
carries four content bytes per frame:

- bit 33 = 1 marks a Unicode extension frame; bit 34 is EOM on the last frame
- 1–16 frames, content limited to **63 UTF-8 bytes**
- the final frame carries three content bytes plus a CRC-8 byte; padding is `0x20`
- CRC-8/ATM: polynomial `0x07`, initial value `0`, no reflection, no final XOR
- a missing frame inserts `EF BF BD 20` (U+FFFD + space) to keep byte alignment
- a CRC failure appends the localized `[checksum missing]` marker

Native text uses the original JTTY grammar and remains interoperable with WSJT-X 3.2.

## Interoperability with JTTY Unicode for Windows

JTTY Unicode for Android and JTTY Unicode for Windows use the same over-the-air
protocol and decode each other directly:

- **Native JTTY frames** are identical to WSJT-X 3.2 and work in both directions.
- **Unicode extension frames** (bit 33, four UTF-8 bytes per frame, CRC-8, up to
  63 bytes in 1–16 frames) work in both directions.
- No gateway, bridge or text conversion is required: both programs transmit and
  receive the same GFSK waveform at the same audio offset.
- The Android whole-passband decoder (300–2700 Hz) and the Windows wideband search
  decode the signal without forcing the Rx frequency marker onto the sender's
  audio offset.
- Verified with **Android 0.1.0.261122** and **Windows 3.2.0-rc1.261122**.

For Unicode text, both ends need a build with the Unicode extension
(Android 0.1.0.261122+ / Windows 3.2.0-rc1.261122+). Native JTTY remains
compatible with unmodified WSJT-X 3.2.

---

## Install

Grab the latest APK from the Releases page, or build it yourself.

```bash
cd jtty_unicode

# Windows
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

Build requirements:

- JDK 17
- Android SDK 36
- NDK 25.1.8937393
- AGP 8.9.1 / Gradle 9.3.0

The debug APK is written to:

```
jtty_unicode/app/build/outputs/apk/debug/app-debug.apk
```

> **Windows build note:** the four `hamlib/prebuilt/<abi>/libhamlib.a` archives are
> intentionally not committed (see `hamlib/.gitignore`). On Windows, run
> `jtty_unicode/app/src/main/cpp/hamlib/build_android.sh` once under WSL before
> `gradlew.bat assembleDebug`, or copy the `prebuilt/` directory from a working
> checkout. Linux, macOS and CI build it automatically.

Install on a connected device:

```bash
gradlew.bat installDebug
```

Current release:

- **Version:** `0.1.0.261122`
- **Application ID:** `com.bi3bju.jtty_unicdoe`
- **Display name:** JTTY Unicode
- **Minimum Android:** 8.0 (API 24)
- **Target Android:** 16 (API 36)

---

## Native code

The JTTY codec lives in `jtty_unicode/app/src/main/cpp/jtty_lib/` and is a clean-room C11
implementation written for this project from the published WSJT-X 3.2 JTTY protocol
description and source-coding specification. The JNI glue is in
`jtty_unicode/app/src/main/cpp/jttyaf_glue/` and is built by the NDK/CMake toolchain into
`libjttyaf.so`.

The same tree also carries the FT8AF-derived support stack:

- `ft8af_glue/` — FFT/waterfall, resampling and hamlib JNI
- `hamlib/` — prebuilt hamlib libraries for all four Android ABIs
- `libusb/` — USB audio and serial transport
- `kissfft/` — FFT support

Unicode extension frames are implemented in `jtty_lib/jtty_ext.c` (with
`jtty_lib/jtty_ext.h`); the receiver accepts extension frames in `jtty_lib/jtty_fec.c`
and `jtty_lib/jtty_rx.c`. Host-side golden-vector tests live in
`jttyaf_glue/test_jtty_ext.c`.

---

## Heritage

JTTY Unicode is built from [JTTYAF](https://github.com/patrickrb/JTTYAF), which is in turn
a fork of [FT8AF](https://github.com/patrickrb/FT8AF) and
[FT8CN](https://github.com/N0BOY/FT8CN) by **BG7YOZ** (hosted by **N0BOY**). The rig CAT
control, USB serial/audio stack, theming, and logbook all carry over from that lineage; the
FT8 engine was replaced by the clean-room JTTY codec.

Massive thanks to the FT8CN authors — none of this exists without their work — and to the
WSJT-X team (K1JT, K9AN, G4KLA, N9ADG, DL3WDG, W3SZ, KJ5HST, KD0BTO) for designing and
documenting JTTY.

## License

MIT. See [LICENSE](LICENSE).

## Privacy

No ads, no analytics, no telemetry. See [privacy.md](privacy.md).

---

## About

Built by:

- **Patrick Burns — [K1AF](https://www.qrz.com/db/K1AF)**
- **Reid — [N0RC](https://www.qrz.com/db/N0RC)** (co-pilot, road-trip debugger,
  all-around enabler)
- **BI3BJU** — Unicode extension and Android maintenance

Project: <https://github.com/BI3BJU/JTTY_Unicode>

73.
