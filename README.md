# Distress Detector

Distress Detector is an Android app that listens for speech on-device, processes it with a local Whisper model, and raises an alert when English output contains three consecutive "help" tokens. The app is designed to keep microphone audio and inference local to the device and to avoid saving raw speech audio.

## What it does

- Starts a foreground microphone service when the user taps Start listening.
- Whisper mode segments speech and transcribes it locally with the model packaged in this build.
- Psychoacoustic mode analyzes microphone audio directly for help/bachao-shaped and scream-like events. It does not run Whisper or produce text.
- Whisper mode detects repeated help phrases in English transcript output and plays a sound and vibrates when triggered.
- Psychoacoustic scores and decisions are experimental display information and do not trigger notifications.

## Privacy and on-device processing

- Microphone audio is processed locally on the device.
- Speech audio is not saved to disk.
- Each speech segment is discarded after processing.
- The app uses a foreground service because the microphone needs to stay active while the user is listening.

## Current model configuration

The app supports a build-time Whisper model choice:

- Default: `tiny`
- Alternate: `small`

The selected model is staged from `models/` into the APK during build. Only the selected file is packaged in the app binary; the downloaded model files remain in the `models/` directory for reuse across builds.

## Project structure

- `app/` — Android app source and native C++ bridge
- `models/` — downloaded Whisper model files retained across builds
- `third_party/whisper.cpp/` — whisper.cpp source used by the native layer
- `tools/setup.ps1` — local toolchain setup helper
- `Makefile` — convenience build and install commands

## Build requirements

You need:

- Android SDK
- Java JDK 17+
- Gradle 8.x
- Android NDK 30.x (configured in the app build)

## Build commands

From the project root:

```powershell
# Default Tiny model
gradle assembleDebug

# Small model
gradle assembleDebug -PwhisperModel=small
```

The project also provides Makefile shortcuts:

```powershell
make build
make install
make run
```

## Notes

- This app is intended for local safety monitoring and is not a medical device.
- The psychoacoustic detector is experimental and does not trigger alerts. It runs only when selected.
- Emergency calling is out of scope for this project.
- Device reliability should only be claimed after spoken validation on physical hardware.

## Important limitation

The project guidance explicitly states that device reliability cannot be claimed until transcription has been checked on a real handset. The app is built for a grounded on-device workflow, but actual field performance still needs hardware validation.
