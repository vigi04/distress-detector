# TCF acoustic distress engine — integration notes

This is the detection core of the Distress Call Monitor (The Curious Fold), packaged so it can be
added to another Android app as an alternative engine — for example as a third option next to
"Whisper Tiny" and "Whisper Small" in a model drop-down.

It is **pure Kotlin, no dependencies** (no androidx, no native code, no NDK, no model files):
nine source files, ~60 KB compiled. Copy the `core/` folder into your project and it builds.

## What it does

A rule-based, psychoacoustic detector. It does **not** transcribe speech. From the raw
microphone stream it computes, every 21 ms: level, f0 (pitch), alpha ratio, Hammarberg index,
spectral flatness and a roughness proxy. It cuts the stream into voiced events, tags each event
by *shape* — "help" (burst → vowel → stop) or "bachao" (voiced onset → mid-word burst → vowel) —
scores the *tone* of each event against a calm baseline (0–1 distress score), and fires on one of
two paths:

- **Word path**: ≥ 3 word-shaped events within 6 s whose mean distress is ≥ 0.50 → ALERT.
  Below 0.50 it reports WORD_ONLY (word heard, tone calm) and does not alert.
- **Scream path**: one voiced event ≥ 0.3 s with distress ≥ 0.85 and roughness ≥ +2σ → ALERT.

Because it works on the shape of the word, "heeeeelp" and "help me, help me, help me" both count,
and the scream path needs no word at all. Costs about 2–3 % of one CPU core; no battery concern.

## Wiring it in (three steps)

### 1. Copy the folder

```
core/*.kt  →  app/src/main/java/com/thecuriousfold/distress/core/
```
Keep the package name `com.thecuriousfold.distress.core` (or rename consistently in all files).

### 2. Feed it audio

The engine takes mono float samples in −1..1 at **any sample rate**: it scales its frame sizes so the
analysis hop stays 21.3 ms. 16 kHz (your current capture) is fine; 48 kHz gives a slightly better
roughness estimate. Any chunk size works.

```kotlin
import com.thecuriousfold.distress.core.*

val engine = DistressEngine(fs = 16000, listener = object : EngineListener {
    override fun onFrame(fr: FrameFeatures) { /* optional: live meters, every 21 ms, audio thread */ }
    override fun onEvent(record: EventRecord) {
        // every voiced utterance that ended — audio thread, so post to main for UI
        val e = record.event; val d = record.decision
        // e.shape      : Shape.HELP / Shape.BACHAO / Shape.NONE
        // record.score.value : 0..1 distress; record.score.drivers : top-3 features behind it
        // d.level      : AlertLevel.STANDBY / WORD_ONLY / ALERT
        // d.path       : Path.WORD / Path.SCREAM when d.level == ALERT
        // d.message    : human-readable reason
        // record.audio : the event's samples (pre/post-roll included) for playback or WAV export
        if (d.level == AlertLevel.ALERT) showAlert(d.message)
    }
    override fun onCalibrationFinished(success: Boolean, baseline: Baseline) { }
})

// in your capture loop (your MicrophoneCapture already produces FloatArray chunks):
engine.process(samples, count)

// when stopping:
engine.stop()
```

If you keep the samples as `ShortArray` from AudioRecord, convert with `s / 32768f`.
The engine is single-threaded: call `process()` from one thread only (the capture thread is ideal).

### 3. Optional extras

- **Personal baseline**: `engine.startCalibration()` while the owner reads calmly for 6 s;
  `onCalibrationFinished` returns the baseline. Persist `baseline.toString()` and restore with
  `Baseline.fromString(s)` into `engine.scorer.baseline`. Without this the default adult baseline is used.
- **Mic blanking**: if you play an alert sound, set `engine.blankUntilT = engine.nowT + 1.0` first so the
  beep is not analysed as a new event.
- **CSV / WAV export**: `CsvExport.build(records)` and `WavWriter.write(stream, record.audio, record.sampleRate)`
  — the CSV has every feature, z-score, decision and label per event, which is what the recordings
  stage needs. Set `record.label` from your UI.
- **Running both engines at once**: feed the same chunks to Whisper and to this engine. The CSV
  then lets you compare, per utterance, what Whisper transcribed and what the acoustic engine decided.

## Thresholds

All numbers live in `Config.kt`, with comments. They are hand-set starting values from the
literature on frightened speech and will be re-fitted from labelled recordings. Please don't tune
them silently — change `Config.kt` and the Python reference (`reference/distress_logic.py`) together,
so the two implementations stay identical.

## Verifying

`reference/distress_logic.py` is the reference implementation (NumPy only); `reference/testdata/`
has ten synthetic recordings at 48 kHz and `testdata16k/` the same at 16 kHz. The Kotlin core was
run on both sets and matched the Python output on every event and decision. To run the Python:

```
python reference/distress_logic.py reference/testdata/02_frightened_help_x3.wav
```

## Files

```
core/Config.kt          every tunable number, rate scaling
core/Dsp.kt             FFT, semitones, helpers
core/FrameAnalyzer.kt   frame features + roughness proxy
core/EventSegmenter.kt  events, help/bachao shapes, per-event features
core/DistressScorer.kt  z-score fusion, calibration
core/DecisionEngine.kt  word path / scream path
core/DistressEngine.kt  the pipeline (entry point), audio ring for exports
core/Export.kt          WAV and CSV writers
reference/              Python reference + test recordings
```

Contact: Alokeparna (Luna) Ray, The Curious Fold.
