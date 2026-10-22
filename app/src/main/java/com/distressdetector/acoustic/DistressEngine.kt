package com.thecuriousfold.distress.core

/** Acoustic features and decision for one detected event. */
class EventRecord(
    val event: SpeechEvent,
    val score: Score,
    val decision: Decision,
    val baselineKind: String
)

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
    private var totalSamples = 0L

    fun process(x: FloatArray, count: Int = x.size) {
        totalSamples += count
        for (fr in analyzer.process(x, count)) {
            listener.onFrame(fr)
            scorer.feedCalibration(fr)?.let { ok -> listener.onCalibrationFinished(ok, scorer.baseline) }
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
        listener.onEvent(EventRecord(ev, score, decision, scorer.baseline.kind))
    }

    fun startCalibration() = scorer.startCalibration(totalSamples.toDouble() / fs)
    fun reset() = decider.reset()
}
