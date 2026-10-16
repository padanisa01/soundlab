# AudioFX

An Android app that applies audio effects to a track: **Warmth** (slider),
**Reverb** (presets: studio, concert hall, bathroom, cave), and **Echo** (slider).

The DSP is written in **Python** and built with **Gradle** via
[Chaquopy](https://chaquo.com/chaquopy/) (17.1.0), the Python SDK for Android.
The same Python module is unit-tested on desktop, so the audio engine is
verified without a JVM or the Android SDK.

## Layout

```
audiofx-app/
├── settings.gradle / build.gradle      # root Gradle project; `testPython` task
├── app/
│   ├── build.gradle                    # Android app + Chaquopy plugin config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/audiofx/app/MainActivity.java   # thin UI shell
│       ├── python/audiofx/             # the actual app logic (Python)
│       │   ├── effects.py              # Warmth, Reverb, Echo
│       │   ├── pipeline.py             # EffectChain + WAV I/O
│       │   └── android_bridge.py       # entry point called from Java
│       └── res/layout/activity_main.xml
├── tests/                              # unittest suite (stdlib only)
└── tools/verify.py                     # compile check + tests + scaffold check
```

## Effects

| Effect  | Control | Implementation |
|---------|---------|----------------|
| Warmth  | slider 0–100% | low-passed signal through tanh saturation, mixed under dry; 0% is bit-identical bypass |
| Reverb  | studio / concert_hall / bathroom / cave | convolution with a synthesized decaying-noise impulse response (per-preset decay, pre-delay, brightness, wet mix) |
| Echo    | slider 0–100% | feedback delay (320 ms); slider scales echo level + feedback; 0% is bit-identical bypass |

Effect order is fixed: Warmth → Reverb → Echo.

NumPy is an optional fast path: reverb uses overlap-add FFT convolution when
NumPy is present (bundled in the APK via Chaquopy `pip`), otherwise a
pure-Python fallback. Warmth and Echo are pure Python either way.

## Verify without the Android SDK

```bash
python3 tools/verify.py
```

This (1) byte-compiles every Python file shipped in the APK, (2) runs the 28
unit tests, and (3) static-checks the Android/Gradle scaffold (plugin ids,
manifest, Chaquopy bootstrap, UI ids). The same check is wired into Gradle:

```bash
gradle testPython
```

## Build the APK

Prerequisites: **JDK 17** (AGP 8.7 will not run on Java 8/11 — if Gradle
reports "Dependency requires at least JVM runtime version 11", point it at
17 via `JAVA_HOME` or `org.gradle.java.home` in `~/.gradle/gradle.properties`),
plus the Android SDK (platform 34, build-tools).

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Chaquopy downloads its Maven artifacts and the NumPy wheel on first build
(minSdk 24, ABIs arm64-v8a + x86_64).

## Use

1. Open the app, tap **Pick audio** and choose a WAV file.
2. Set Warmth, pick a Reverb preset, set Echo.
3. Tap **Apply effects**, then **Play result**.

Notes and limits (v1):

- Input must be WAV (8/16-bit PCM); other formats picked from the file
  picker will report an error. MP3 decode (via Android `MediaCodec`) is the
  natural next step.
- Long files at high reverb decay take a few seconds on device; processing
  runs on a background thread.
- The `wave`-module I/O is mono; stereo inputs are mixed down.
