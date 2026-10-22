package com.thecuriousfold.distress.core

/**
 * All tunable numbers in one place. They mirror distress_logic.py (the reference
 * implementation) and distress_monitor.html (the phone-browser prototype) exactly.
 *
 * Every value is a hand-set starting point chosen from what is known about frightened
 * speech; these are the numbers the labelled recordings will later re-fit.
 */
object Config {
    // ---- framing: HOP=1024, NFFT=2048 at 48 kHz (the page's numbers); scaled to other rates so
    //      the hop stays 21.3 ms and every frame-count rule below keeps its meaning ----
    const val REFERENCE_RATE = 48000
    const val HOP_AT_REFERENCE = 1024
    const val NFFT_AT_REFERENCE = 2048
    fun hopFor(fs: Int): Int = Math.round(HOP_AT_REFERENCE.toDouble() * fs / REFERENCE_RATE).toInt()
    /** Window ≈ 42.7 ms, rounded up to a power of two (2048 at 48 kHz, 1024 at 16 kHz). */
    fun nfftFor(fs: Int): Int { var n = 1; val want = NFFT_AT_REFERENCE.toDouble() * fs / REFERENCE_RATE; while (n < want) n = n shl 1; return n }
    /** Pitch is tracked on a copy decimated to ≈16 kHz (3:1 at 48 kHz, 1:1 at 16 kHz). */
    fun pitchDecimationFor(fs: Int): Int = maxOf(1, Math.round(fs / 16000.0).toInt())
    /** Envelope point every ≈2.7 ms (128 samples at 48 kHz) so the modulation analysis reaches 150 Hz. */
    fun envSubFor(fs: Int): Int = maxOf(8, Math.round(ENV_SUB_AT_REFERENCE.toDouble() * fs / REFERENCE_RATE).toInt())
    /** Frames of context kept before a voiced event (for the /h/ burst). */
    const val PRE_FRAMES = 8
    /** Unvoiced frames that end an event. */
    const val END_GAP = 6
    /** Unvoiced frames that are still "inside" an event (short dips, consonants). */
    const val INNER_GAP = 5
    /** Events with fewer voiced frames than this (< ~85 ms) are ignored. */
    const val MIN_VOICED_FRAMES = 4

    // ---- voicing ----
    const val VOICING_CORR = 0.55
    const val VOICING_SNR = 10.0
    const val F0_MIN = 60.0
    const val F0_MAX = 1000.0
    /** Octave guard: the shortest lag that is a local peak within this share of the maximum wins. */
    const val OCTAVE_GUARD = 0.92

    // ---- noise floor (tracked on unvoiced frames only) ----
    const val FLOOR_INIT = -60.0
    const val FLOOR_ALPHA = 0.005

    // ---- roughness proxy ----
    /** Envelope decimation at 48 kHz: samples per envelope point (see envSubFor). */
    const val ENV_SUB_AT_REFERENCE = 128
    /** Envelope points in the modulation analysis. */
    const val ENV_N = 128
    const val ROUGH_MIN_DEPTH = 0.05
    const val ROUGH_BAND_LO = 30.0
    const val ROUGH_BAND_HI = 150.0

    // ---- "help"-shaped ----
    const val BURST_FLATNESS = 0.25
    const val BURST_ABOVE_FLOOR_DB = 6.0
    const val STOP_DROP_DB = 12.0
    const val STOP_POST_FRAMES = 3
    const val HELP_DUR_MIN = 0.1
    const val HELP_DUR_MAX = 3.0
    const val HELP_W_BURST = 0.45
    const val HELP_W_DUR = 0.30
    const val HELP_W_STOP = 0.25
    const val HELP_THRESHOLD = 0.55

    // ---- "bachao"-shaped (बचाओ / বাঁচাও): voiced "ba-" → flat unvoiced "-ch-" → voiced "-ao" → decay ----
    const val BACHAO_ONSET_MIN = 0.04
    const val BACHAO_ONSET_MAX = 0.40
    const val BACHAO_GAP_MIN = 0.03
    const val BACHAO_GAP_MAX = 0.20
    const val BACHAO_GAP_FLATNESS = 0.25
    /** The "-ch-" burst must have at least this alpha ratio (dB): fricative energy sits above 1 kHz. */
    const val BACHAO_GAP_ALPHA_MIN = -5.0
    const val BACHAO_CORE_MIN = 0.12
    const val BACHAO_CORE_MAX = 3.0

    // ---- distress score ----
    const val Z_CLIP = 3.0
    const val SCORE_K = 1.6
    const val SCORE_B = 0.9

    // ---- decision ----
    const val WINDOW_S = 6.0
    const val N_REPEAT = 3
    const val WORD_DISTRESS = 0.50
    const val SCREAM_DISTRESS = 0.85
    const val SCREAM_MIN_DUR = 0.30
    const val SCREAM_ROUGH_Z = 2.0
    /** Audio is ignored for this long after an audible alert so the beep is not recorded as an event. */
    const val BLANKING_S = 1.0

    // ---- calibration ----
    const val CAL_SECONDS = 6.0
    const val CAL_MIN_VOICED_FRAMES = 30

}

/**
 * Per-event features that enter the distress score, in the order of distress_logic.py.
 * sign/weight: + means "more of this = more distress".
 */
enum class Feature(val key: String, val label: String, val weight: Double) {
    ROUGH("rough", "roughness", 1.0),
    F0ST("f0st", "pitch", 0.8),
    F0RANGE("f0range", "pitch range", 0.6),
    ALPHA("alpha", "alpha ratio", 0.6),
    HAM("ham", "Hammarberg", -0.5),
    JITTER("jitter", "jitter", 0.5),
    LEVEL("level", "level", 0.4),
    DUR("dur", "duration", 0.3),
}

/** Speaker baseline: mean and sd of each scored feature for calm speech. */
class Baseline(val mean: DoubleArray, val sd: DoubleArray, val kind: String) {
    fun copy(kind: String = this.kind) = Baseline(mean.copyOf(), sd.copyOf(), kind)

    companion object {
        /** Mean/sd of event features for a generic calm adult voice (distress_logic.py default_baseline). */
        fun default(): Baseline {
            val m = DoubleArray(Feature.entries.size)
            val s = DoubleArray(Feature.entries.size)
            fun set(f: Feature, mean: Double, sd: Double) { m[f.ordinal] = mean; s[f.ordinal] = sd }
            set(Feature.F0ST, 33.0, 4.0)
            set(Feature.F0RANGE, 4.0, 2.5)
            set(Feature.JITTER, 0.35, 0.25)
            set(Feature.LEVEL, -30.0, 6.0)
            set(Feature.ALPHA, -14.0, 5.0)
            set(Feature.HAM, 26.0, 6.0)
            set(Feature.ROUGH, 0.18, 0.10)
            set(Feature.DUR, 0.35, 0.25)
            return Baseline(m, s, "default")
        }

        fun fromString(s: String): Baseline? = try {
            val parts = s.split(";")
            val kind = parts[0]
            val m = parts[1].split(",").map { it.toDouble() }.toDoubleArray()
            val sd = parts[2].split(",").map { it.toDouble() }.toDoubleArray()
            if (m.size == Feature.entries.size && sd.size == Feature.entries.size) Baseline(m, sd, kind) else null
        } catch (e: Exception) { null }
    }

    override fun toString(): String =
        kind + ";" + mean.joinToString(",") + ";" + sd.joinToString(",")
}
