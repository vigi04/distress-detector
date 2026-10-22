package com.thecuriousfold.distress.core

import kotlin.math.abs

/** The word a voiced event is shaped like, if any. */
enum class Shape(val label: String) {
    NONE("voiced"),
    HELP("help"),
    BACHAO("bachao"),
}

/** One voiced utterance with its tone features (event_features in distress_logic.py). */
class SpeechEvent(
    val id: Long,
    /** First frame index of the event. */
    val startFrame: Long,
    val tStart: Double,
    /** (start + frames + post) · hop, as in the reference. */
    val tEnd: Double,
    /** Scored features in [Feature] order. */
    val scored: DoubleArray,
    val f0Hz: Double,
    val f0Slope: Double,
    val levelMax: Double,
    val burst: Boolean,
    val stop: Boolean,
    val helpScore: Double,
    val shape: Shape,
    /** For a bachao-shaped event split across two events: the id of the "ba-" event it absorbs. */
    val absorbsEventId: Long?,
    /** Frames of the event (voiced and inner gaps), for display. */
    val voicedFrames: Int,
) {
    val dur get() = scored[Feature.DUR.ordinal]
    val f0St get() = scored[Feature.F0ST.ordinal]
    val f0Range get() = scored[Feature.F0RANGE.ordinal]
    val jitter get() = scored[Feature.JITTER.ordinal]
    val level get() = scored[Feature.LEVEL.ordinal]
    val alpha get() = scored[Feature.ALPHA.ordinal]
    val ham get() = scored[Feature.HAM.ordinal]
    val rough get() = scored[Feature.ROUGH.ordinal]
    val helpLike get() = shape == Shape.HELP
    val wordShaped get() = shape != Shape.NONE
}

/**
 * Segments the frame stream into voiced events and computes per-event features.
 * Mirrors Detector._on_frame / _finish_event / event_features in distress_logic.py,
 * plus the "bachao" shape.
 *
 *  help   = [flat unvoiced burst /h/ in the 8 pre-frames] → voiced core 0.1–3 s → [abrupt stop /p/]
 *           helpScore = 0.45·burst + 0.30·durOk + 0.25·stop ≥ 0.55
 *  bachao = short voiced onset "ba-" (0.04–0.4 s) → flat unvoiced gap "-ch-" (0.03–0.2 s)
 *           → voiced core "-ao" (0.12–3 s) that ends by decay, not by a stop.
 *           Found either inside one event (the gap is shorter than INNER_GAP and gets absorbed)
 *           or across two consecutive events.
 */
class EventSegmenter(private val hopSeconds: Double) {
    private val frames = ArrayDeque<FrameFeatures>()       // rolling, for pre-context

    private class Open(val start: Long) {
        val frames = ArrayList<FrameFeatures>()
        var gap = 0
        var pre: List<FrameFeatures> = emptyList()
        val post = ArrayList<FrameFeatures>()
    }
    private var ev: Open? = null
    private var nextId = 1L
    private var previous: SpeechEvent? = null
    private var previousLastVoicedT = 0.0

    fun process(fr: FrameFeatures, floor: Double): SpeechEvent? {
        frames.addLast(fr)
        while (frames.size > 400) frames.removeFirst()
        val e = ev
        if (e == null) {
            if (fr.voiced) {
                val o = Open(fr.idx)
                o.frames.add(fr)
                // pre-context: the PRE_FRAMES frames before this one
                val list = frames.toList()
                o.pre = list.subList(maxOf(0, list.size - 1 - Config.PRE_FRAMES), list.size - 1)
                ev = o
            }
            return null
        }
        if (fr.voiced) {
            if (e.gap in 1..Config.INNER_GAP) e.frames.addAll(e.post)
            e.frames.add(fr); e.gap = 0; e.post.clear()
            return null
        }
        e.gap++
        e.post.add(fr)
        return if (e.gap >= Config.END_GAP) finish(floor) else null
    }

    fun flush(floor: Double): SpeechEvent? = if (ev != null) finish(floor) else null

    private fun finish(floor: Double): SpeechEvent? {
        val e = ev ?: return null
        ev = null
        val vf = e.frames.filter { it.voiced }
        if (vf.size < Config.MIN_VOICED_FRAMES) return null

        val dur = e.frames.size * hopSeconds
        val lastV = vf.last()
        val burst = e.pre.any { !it.voiced && it.flat > Config.BURST_FLATNESS && it.level > floor + Config.BURST_ABOVE_FLOOR_DB }
        val after = e.post.take(Config.STOP_POST_FRAMES)
        val drop = if (after.isNotEmpty()) lastV.level - after.minOf { it.level } else 0.0
        val stop = drop >= Config.STOP_DROP_DB
        val durOk = dur >= Config.HELP_DUR_MIN && dur <= Config.HELP_DUR_MAX
        val helpScore = Config.HELP_W_BURST * (if (burst) 1 else 0) + Config.HELP_W_DUR * (if (durOk) 1 else 0) +
                Config.HELP_W_STOP * (if (stop) 1 else 0)
        val helpLike = helpScore >= Config.HELP_THRESHOLD

        val f0s = DoubleArray(vf.size) { Dsp.semitone(vf[it].f0) }
        val ts = DoubleArray(vf.size) { vf[it].t }
        var jit = 0.0
        for (i in 1 until f0s.size) jit += abs(f0s[i] - f0s[i - 1])
        jit /= maxOf(1, f0s.size - 1)
        val slope = Dsp.linSlope(ts, f0s)
        val f0m = Dsp.mean(f0s)

        val scored = DoubleArray(Feature.entries.size)
        scored[Feature.DUR.ordinal] = dur
        scored[Feature.F0ST.ordinal] = f0m
        scored[Feature.F0RANGE.ordinal] = f0s.max() - f0s.min()
        scored[Feature.JITTER.ordinal] = jit
        scored[Feature.LEVEL.ordinal] = vf.sumOf { it.level } / vf.size
        scored[Feature.ALPHA.ordinal] = vf.sumOf { it.alpha } / vf.size
        scored[Feature.HAM.ordinal] = vf.sumOf { it.ham } / vf.size
        scored[Feature.ROUGH.ordinal] = vf.sumOf { it.rough } / vf.size

        // ---- bachao shape -------------------------------------------------------------
        // The signature is a flat unvoiced burst *between* two voiced runs (the "-ch-" of
        // ba-ch-ao); a /h/ burst sits *before* the voice, so the two shapes cannot be confused.
        var bachao = false
        var absorbs: Long? = null
        // (a) inside this one event: the "-ch-" gap was shorter than INNER_GAP and got absorbed
        val runs = runsOf(e.frames)
        if (runs.size >= 3) {
            val core = runs[runs.size - 1]; val gapRun = runs[runs.size - 2]; val onset = runs[runs.size - 3]
            if (core.voiced && !gapRun.voiced && onset.voiced &&
                bachaoFits(onset.frames, gapRun.frames, core.frames, floor)) bachao = true
        }
        // (b) across two events: the previous event was "ba-" and this event's pre-context holds "-ch-"
        val prev = previous
        if (!bachao && prev != null) {
            val startT = e.start * hopSeconds
            val gapSeconds = startT - previousLastVoicedT - hopSeconds
            if (gapSeconds >= Config.BACHAO_GAP_MIN && gapSeconds <= Config.BACHAO_GAP_MAX) {
                val gapFrames = frames.filter { it.t > previousLastVoicedT && it.idx < e.start }
                val onsetFrames = frames.filter { it.idx >= prev.startFrame && it.t <= previousLastVoicedT && it.voiced }
                if (gapFrames.isNotEmpty() && onsetFrames.isNotEmpty() &&
                    bachaoFits(onsetFrames, gapFrames, vf, floor)) { bachao = true; absorbs = prev.id }
            }
        }

        val shape = when {
            bachao -> Shape.BACHAO
            helpLike -> Shape.HELP
            else -> Shape.NONE
        }
        val tEnd = (e.start + e.frames.size + e.post.size) * hopSeconds
        val out = SpeechEvent(
            id = nextId++, startFrame = e.start, tStart = e.start * hopSeconds, tEnd = tEnd,
            scored = scored, f0Hz = Dsp.semitoneToHz(f0m), f0Slope = slope,
            levelMax = vf.maxOf { it.level }, burst = burst, stop = stop, helpScore = helpScore,
            shape = shape, absorbsEventId = absorbs, voicedFrames = vf.size,
        )
        previous = out
        previousLastVoicedT = lastV.t
        return out
    }

    private class Run(val voiced: Boolean) { val frames = ArrayList<FrameFeatures>() }

    private fun runsOf(fs: List<FrameFeatures>): List<Run> {
        val runs = ArrayList<Run>()
        for (f in fs) {
            if (runs.isEmpty() || runs.last().voiced != f.voiced) runs.add(Run(f.voiced))
            runs.last().frames.add(f)
        }
        return runs
    }

    private fun bachaoFits(onset: List<FrameFeatures>, gap: List<FrameFeatures>, core: List<FrameFeatures>, floor: Double): Boolean {
        val onsetS = onset.size * hopSeconds
        val gapS = gap.size * hopSeconds
        val coreS = core.size * hopSeconds
        if (onsetS < Config.BACHAO_ONSET_MIN || onsetS > Config.BACHAO_ONSET_MAX) return false
        if (gapS < Config.BACHAO_GAP_MIN || gapS > Config.BACHAO_GAP_MAX) return false
        if (coreS < Config.BACHAO_CORE_MIN || coreS > Config.BACHAO_CORE_MAX) return false
        if (gap.any { it.voiced }) return false
        // the "-ch-" must be audible, spectrally flat and carry high-frequency (fricative) energy
        val audible = gap.filter { it.level > floor + Config.BURST_ABOVE_FLOOR_DB }
        if (audible.isEmpty()) return false
        if (audible.sumOf { it.flat } / audible.size < Config.BACHAO_GAP_FLATNESS) return false
        if (audible.maxOf { it.alpha } < Config.BACHAO_GAP_ALPHA_MIN) return false
        return true
    }
}
