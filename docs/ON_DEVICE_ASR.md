# On-device ASR (whisper.cpp) – setup

The app transcribes recordings on the phone with **whisper.cpp** through a small JNI bridge
(`android/app/src/main/cpp/whisper_jni.cpp`). Decoding uses the best pilot setting
`lid_bias_prompt` (TR/EN-only language ID defaulting to Turkish + vocabulary prompt), implemented
in `WhisperEngine.kt` — keep its constants in sync with `ml/asr_pilot/benchmark_whisper.py`.

## 1. One-time setup

1. **whisper.cpp sources** (git submodule, from the repo root):
   ```
   git submodule add https://github.com/ggml-org/whisper.cpp android/app/src/main/cpp/whisper.cpp
   ```
   Record the commit you build against in the progress log (`git -C android/app/src/main/cpp/whisper.cpp log -1 --oneline`).
2. **Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools**: tick
   **NDK (Side by side)** and **CMake**, Apply.
3. Sync Gradle and run. The first native build compiles whisper.cpp and takes several minutes.

## 2. Models (not bundled in the APK)

Download quantized models (browser is fine):
- base: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin (~60 MB)
- small: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin (~190 MB)

Copy them to the phone (app installed and opened at least once):
```
adb push ggml-base-q5_1.bin  /sdcard/Android/data/com.ben.karmaasistan/files/models/
adb push ggml-small-q5_1.bin /sdcard/Android/data/com.ben.karmaasistan/files/models/
```
In the app, tap the **ASR** pill to rescan / switch models.

## 3. Using it

- Record a command → after stopping, the transcript, chosen language, latency and confidence appear.
- **Toplu test** runs ASR over every recording in `recordings/manifest.csv` with the selected model and
  writes `recordings/asr_<model>_<time>.csv`. Run it once per model.
- Pull the recordings folder (see `data/README.md`) and score:
  ```
  cd ml
  .venv\Scripts\python.exe asr_pilot\score_device.py --recordings ..\data\recordings
  ```
  → on-device latency (median / p90, H4) and WER per speaker.

## Speed settings (why a short word once took ~11 s)

- **Encoder window (⏱ pill: 10 s / 15 s / 30 s).** Whisper pads every input to 30 s. A smaller window
  (`audio_ctx`) makes the encoder several times faster; commands longer than the window automatically
  use the full 30 s. Smaller windows *may* cost accuracy — compare them with Toplu test (the window is
  part of the result label, e.g. `base-q5_1_ctx512`).
- **Bounded retries.** Retry at a higher temperature only for repetition loops, max 2 retries, max 48
  tokens. (Retrying on low confidence multiplied the time for short or unknown words.)
- **ARM dot-product / fp16 instructions** are enabled for arm64 builds (`armv8.2-a+dotprod+fp16`). The
  app shows the active CPU features under the pills — check that `DOTPROD` appears.
- Language detection and transcription each run the encoder once, so both benefit from the window.

## Notes
- The native code is always built optimized (Release); a debug build of whisper.cpp is ~10× slower.
- Latency measured on the **emulator** is not valid for H4 — use the real phone.
