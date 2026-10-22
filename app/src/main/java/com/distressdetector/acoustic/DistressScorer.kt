package com.thecuriousfold.distress.core

/** Result of scoring one event. */
class Score(
    /** 0–1 distress score. */
    val value: Double,
    /** Clipped z-score of each feature against the baseline, in [Feature] order. */
    val z: DoubleArray,
) {
    /** Weighted, signed contribution of each feature. */
    fun contribution(f: Feature) = f.weight * z[f.ordinal]
    /** The three features that pushed the score up the most, highest first. */
    val drivers: List<Feature> get() = Feature.entries.sortedByDescending { contribution(it) }.take(3)
    val roughZ: Double get() = z[Feature.ROUGH.ordinal]
}

/**
 * distress = sigmoid( 1.6 · ( Σ w·z / Σ|w| − 0.9 ) ), z clipped to ±3 — distress_score() in the reference.
 * Kept transparent on purpose: every contribution is retained so a false alarm can be explained.
 */
class DistressScorer(@Volatile var baseline: Baseline = Baseline.default()) {

    fun score(ev: SpeechEvent): Score {
        val b = baseline
        val z = DoubleArray(Feature.entries.size)
        var acc = 0.0; var wsum = 0.0
        for (f in Feature.entries) {
            val i = f.ordinal
            val zz = Dsp.clamp((ev.scored[i] - b.mean[i]) / maxOf(b.sd[i], 1e-3), -Config.Z_CLIP, Config.Z_CLIP)
            z[i] = zz
            acc += f.weight * zz
            wsum += kotlin.math.abs(f.weight)
        }
        val mz = acc / wsum
        return Score(Dsp.sigmoid(Config.SCORE_K * (mz - Config.SCORE_B)), z)
    }

    // ---- calibration (finishCal() in distress_monitor.html) ----------------------------

    private val calFrames = ArrayList<FrameFeatures>()
    @Volatile var calibrating = false
        private set
    private var calEndT = 0.0

    fun startCalibration(nowT: Double) {
        calFrames.clear()
        calibrating = true
        calEndT = nowT + Config.CAL_SECONDS
    }

    /** Returns null while running; true/false when calibration finished on this frame. */
    fun feedCalibration(fr: FrameFeatures): Boolean? {
        if (!calibrating) return null
        if (fr.voiced) calFrames.add(fr)
        if (fr.t < calEndT) return null
        calibrating = false
        return finish()
    }

    private fun finish(): Boolean {
        val vf = calFrames
        if (vf.size < Config.CAL_MIN_VOICED_FRAMES) return false
        val f0s = DoubleArray(vf.size) { Dsp.semitone(vf[it].f0) }
        val jit = DoubleArray(maxOf(0, f0s.size - 1)) { kotlin.math.abs(f0s[it + 1] - f0s[it]) }
        val m = baseline.mean.copyOf(); val s = baseline.sd.copyOf()
        fun set(f: Feature, a: DoubleArray, minS: Double) {
            val mean = Dsp.mean(a); m[f.ordinal] = mean; s[f.ordinal] = maxOf(Dsp.sd(a, mean), minS)
        }
        set(Feature.F0ST, f0s, 2.5)
        set(Feature.LEVEL, DoubleArray(vf.size) { vf[it].level }, 4.0)
        set(Feature.ALPHA, DoubleArray(vf.size) { vf[it].alpha }, 3.0)
        set(Feature.HAM, DoubleArray(vf.size) { vf[it].ham }, 4.0)
        set(Feature.ROUGH, DoubleArray(vf.size) { vf[it].rough }, 0.07)
        set(Feature.JITTER, jit, 0.15)
        // f0 range and duration per utterance are hard to get from a reading: scale to the speaker / keep default
        m[Feature.F0RANGE.ordinal] = maxOf(3.0, Dsp.sd(f0s, Dsp.mean(f0s)) * 2); s[Feature.F0RANGE.ordinal] = 2.5
        val d = Baseline.default()
        m[Feature.DUR.ordinal] = d.mean[Feature.DUR.ordinal]; s[Feature.DUR.ordinal] = d.sd[Feature.DUR.ordinal]
        baseline = Baseline(m, s, "personal")
        return true
    }
}
