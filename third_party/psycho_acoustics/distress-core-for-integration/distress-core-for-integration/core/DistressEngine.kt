package com.thecuriousfold.distress.core

/** A scored event with its audio, as shown in the events list and exported to CSV/WAV. */
class EventRecord(
    val event: SpeechEvent,
    val score: Score,
    val decision: Decision,
    /** Mono samples at [sampleRate]: 8 frames + 50 ms before the start .. 100 ms after the end. */
    val audio: FloatArray,
    val sampleRate: Int,
    val baselineKind: String,
) {
    @Volatile var label: String = ""
    val wallClock: Long = System.currentTimeMillis()
    /** Which path fired on this event, if any ("word", "scream"). */
    val trigger: String get() = when {
        decision.level == AlertLevel.ALERT && decision.path == Path.SCREAM -> "scream"
        decision.level == AlertLevel.ALERT && decision.path == Path.WORD -> "word"
        else -> ""
    }
}

interface EngineListener {
    /** Called on the audio thread for every frame. */
    fun onFrame(fr: FrameFeatures)
    fun onEvent(record: EventRecord)
    fun onCalibrationFinished(success: Boolean, baseline: Baseline)
}

/**
 * The whole pipeline, Android-free: samples in → frames → events → score → decision.
 * Call [process] from the audio thread with any chunk size.
 */
class DistressEngine(val fs: Int, private val listener: EngineListener) {
    val analyzer = FrameAnalyzer(fs)
    private val segmenter = EventSegmenter(analyzer.hopSeconds)
    val scorer = DistressScorer()
    private val decider = DecisionEngine()

    private val ringN = (fs * Config.AUDIO_RING_SECONDS).toInt()
    private val ring = FloatArray(ringN)
    private var ringPos = 0
    private var totalSamples = 0L

    /** Set by the host after an audible alert; audio before this time is ignored (mic blanking). */
    @Volatile var blankUntilT = 0.0

    val nowT: Double get() = totalSamples.toDouble() / fs

    fun process(x: FloatArray, count: Int = x.size) {
        for (i in 0 until count) { ring[ringPos] = x[i]; ringPos = (ringPos + 1) % ringN }
        totalSamples += count
        for (fr in analyzer.process(x, count)) {
            listener.onFrame(fr)
            scorer.feedCalibration(fr)?.let { ok -> listener.onCalibrationFinished(ok, scorer.baseline) }
            if (fr.t < blankUntilT) continue
            segmenter.process(fr, analyzer.floor)?.let { handle(it) }
        }
    }

    fun stop() {
        segmenter.flush(analyzer.floor)?.let { handle(it) }
    }

    private fun handle(ev: SpeechEvent) {
        if (scorer.calibrating) return
        val score = scorer.score(ev)
        val decision = decider.evaluate(ev, score)
        listener.onEvent(EventRecord(ev, score, decision, sliceAudio(ev), fs, scorer.baseline.kind))
    }

    private fun sliceAudio(ev: SpeechEvent): FloatArray {
        val s0 = maxOf(0L, (ev.startFrame - Config.PRE_FRAMES) * analyzer.hop - (0.05 * fs).toLong())
        val s1 = minOf(totalSamples, (ev.tEnd * fs).toLong() + (0.1 * fs).toLong())
        val n = (s1 - s0).toInt()
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val abs = s0 + i
            if (abs < totalSamples - ringN) continue
            out[i] = ring[((abs % ringN + ringN) % ringN).toInt()]
        }
        return out
    }

    fun startCalibration() = scorer.startCalibration(nowT)
    fun reset() = decider.reset()
}
