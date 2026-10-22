# Distress Detector

## Project plan

Build a small Android app with two selectable on-device detection modes: whisper.cpp transcription and psychoacoustic audio analysis. Keep multilingual Tiny Q5_1 as the default model and allow Small Q5_1 selection at build time; package only the selected model and preserve every downloaded model in `models/`. In Whisper mode, sound/vibrate when English output contains three consecutive “help” tokens. Psychoacoustic decisions are experimental and display-only. Emergency calling is out of scope.

## Working rules

- Keep each responsibility in one source of truth.
- Extend existing components before adding new ones, and remove superseded code in the same change.
- Keep microphone audio and inference on-device. Do not save speech audio.
- Use whisper.cpp defaults for decoding and pass user-selected task/language at runtime; keep one native library build for the configured Android ABI.
- Select packaged weights with `-PwhisperModel=tiny` (default) or `-PwhisperModel=small`; model source files in `models/` are retained across builds.
- Do not claim device reliability until transcription has been checked on physical hardware.
- Do not add dependencies unless the implementation needs them. Record why a new dependency is needed.

## Current implementation status

The app has a native setup screen, a user-started microphone foreground service, selectable Whisper and psychoacoustic modes, a thin JNI bridge to whisper.cpp, build-time Tiny/Small model selection, repeated-help phrase detection on English Whisper output with sound/vibration alerts, and an experimental psychoacoustic detector that runs only in its own mode. Psychoacoustic decisions are display-only; thresholds and baseline are not calibrated. Complete spoken mode and alert checks on the Samsung SM-S911U1 before claiming device reliability.
