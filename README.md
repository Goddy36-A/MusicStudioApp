# 🎙 Music Studio — Android App

A full-featured mobile recording studio that lets you **sing or rap over any
instrumental or song in your device's storage**, with real-time vocal processing
including pitch shift, autotune, reverb, echo, and a 3-band EQ.

---

## ✨ Features

| Feature | Details |
|---|---|
| **Backing track player** | Any audio file from device storage (MP3, FLAC, OGG, WAV) |
| **Live microphone recording** | AudioRecord → low-latency vocal capture |
| **Pitch shift** | −12 to +12 semitones in real-time (Sonic WSOLA algorithm) |
| **Tempo control** | 50 % – 200 % without altering pitch |
| **AutoTune** | YIN pitch detection + scale-locked correction (Chromatic, Major, Minor, Pentatonic, Blues) |
| **Reverb** | 6 presets: Small Room → Cathedral (Freeverb algorithm) |
| **Echo / Delay** | 0 – 600 ms delay with adjustable decay |
| **3-Band EQ** | Bass (200 Hz shelf), Mid (1 kHz peak), Treble (6 kHz shelf) — ±12 dB |
| **Volume mix** | Independent vocal and backing-track faders |
| **WAV export** | Lossless 44100 Hz / 16-bit mono recording |
| **Recordings manager** | Play back, share, or delete saved sessions |
| **Waveform visualiser** | Real-time scrolling bar graph + VU meter |

---

## 🏗 Architecture

```
app/
├── audio/
│   ├── AudioEngine.kt          ← Core coordinator (mic → process → mix → output)
│   ├── SonicProcessor.kt       ← WSOLA pitch/tempo processing
│   ├── AutoTuneEngine.kt       ← YIN detection + scale-lock correction
│   ├── EffectsChain.kt         ← Reverb (Freeverb) · Echo · 3-band biquad EQ
│   ├── WavWriter.kt            ← 44100/16-bit WAV encoder (embedded in AudioEngine)
│   └── RecordingService.kt     ← Foreground service (keeps session alive)
├── data/
│   └── Track.kt                ← Track, SessionSettings, Scale, ReverbPreset models
├── ui/
│   ├── studio/StudioFragment   ← Main recording screen + all controls
│   ├── library/LibraryFragment ← MediaStore browser with search
│   ├── export/RecordingsFragment ← Saved recordings: play / share / delete
│   └── views/
│       ├── WaveformView        ← Real-time scrolling bar graph
│       └── VUMeterView         ← Segmented gain meter
├── viewmodel/
│   └── StudioViewModel         ← LiveData state + AudioEngine lifecycle
└── utils/
    ├── AudioUtils.kt           ← WAV read/write, mix, normalize, format helpers
    └── PermissionUtils.kt      ← Mic + storage permission helpers
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android device / emulator running **Android 8.0 (API 26)** or higher
- Microphone hardware

### Clone & build

```bash
git clone https://github.com/<your-username>/MusicStudioApp.git
cd MusicStudioApp
./gradlew assembleDebug
```

The debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

### Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔄 GitHub Workflows

Two automated workflows are included in `.github/workflows/`:

### `ci.yml` — Continuous Integration

Runs on every **push** and **pull request** to `main` or `develop`.

| Step | What it does |
|---|---|
| Lint | `./gradlew lint` — catches XML / code issues |
| Unit tests | `./gradlew testDebugUnitTest` |
| Build debug APK | `./gradlew assembleDebug` |

Artifacts (lint report, test results, APK) are uploaded for 7 days.

### `release.yml` — Signed Release

Triggered when you push a **version tag** (e.g. `v1.0.0`).

```bash
git tag v1.0.0
git push origin v1.0.0
```

It will:
1. Build a release APK
2. Sign it with your keystore
3. Create a GitHub Release and attach the APK automatically

#### Required repository secrets

Go to **Settings → Secrets → Actions** and add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` file: `base64 -i my.jks` |
| `KEYSTORE_PASSWORD` | Store password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Generate a keystore if you don't have one:

```bash
keytool -genkey -v -keystore music-studio.jks \
  -alias music-studio -keyalg RSA -keysize 2048 -validity 10000
```

---

## 📱 Usage Guide

### Recording a session

1. **Studio tab** → tap **Browse Library** to choose a backing track
2. Dial in your settings (pitch, tempo, autotune, effects)
3. Put on headphones (prevents mic bleed from the backing track)
4. Tap **⏺ Record** — a red indicator appears while recording
5. Tap **⏹ Stop Recording** when done
6. The WAV file is saved immediately; a snackbar offers to jump to **Recordings**

### Monitor mode

Tap **🎧 Monitor** to hear your processed voice (pitch + effects) through the
earpiece in real-time **without** recording, useful for dialling in settings.

### Adjusting AutoTune

- Enable the **AutoTune** switch to reveal the controls
- **Strength** 0 % = subtle correction, 100 % = hard T-Pain snap
- **Scale** — pick the key/mode of your backing track for natural results

---

## 🛠 Technical Notes

### Audio latency
The app uses `MediaRecorder.AudioSource.VOICE_PERFORMANCE` for lowest latency.
Total round-trip latency depends on device hardware; typical Android phones
achieve 30 – 80 ms, which is sufficient for singing with headphones.

### Pitch shifting algorithm
`SonicProcessor` implements a simplified WSOLA (Waveform Similarity Overlap-Add)
algorithm in pure Kotlin. This operates in the time domain (no FFT), making it
very efficient for real-time use on mobile hardware.

### AutoTune algorithm
`AutoTuneEngine` uses the **YIN** algorithm (de Cheveigné & Kawahara, 2002) for
fundamental frequency detection, then snaps the detected pitch to the nearest
degree of the selected scale via linear-interpolation resampling. A first-order
low-pass filter smooths pitch jumps to prevent zipper noise.

### Reverb algorithm
`EffectsChain` implements a **Freeverb**-style reverb: 8 Schroeder comb filters
in parallel, followed by 4 Moorer allpass filters in series, tuned to the
device sample rate.

### WAV output
Recordings are 16-bit PCM / 44100 Hz / mono WAV. The `WavWriter` class writes
the 44-byte RIFF header with a placeholder data size, streams samples during
the session, and patches the header on `close()`.

---

## 📋 Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Microphone capture |
| `READ_MEDIA_AUDIO` (API 33+) | Browse music library |
| `READ_EXTERNAL_STORAGE` (API ≤ 32) | Browse music library |
| `FOREGROUND_SERVICE_MICROPHONE` | Keep recording alive when screen is off |
| `WAKE_LOCK` | Prevent CPU sleep during recording |

---

## 📄 License

```
MIT License — feel free to use, modify, and distribute.
```
