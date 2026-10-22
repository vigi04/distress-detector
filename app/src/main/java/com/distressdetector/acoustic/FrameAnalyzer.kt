package com.thecuriousfold.distress.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Features of one hop (≈21 ms at 48 kHz), analysed over the latest NFFT window. */
class FrameFeatures(
    val idx: Long,
    /** Seconds since start: idx * HOP / fs. */
    val t: Double,
    /** dBFS, RMS of the window. */
    val level: Double,
    /** Hz, 0 if unvoiced. */
    val f0: Double,
    /** Autocorrelation peak (0..1). */
    val corr: Double,
    val voiced: Boolean,
    /** dB, energy 1–5 kHz over 50–1000 Hz. */
    val alpha: Double,
    /** dB, Hammarberg index: peak 0–2 kHz minus peak 2–5 kHz. */
    val ham: Double,
    /** Spectral flatness 0.5–8 kHz (0..1). */
    val flat: Double,
    /** Roughness proxy (0..1). */
    val rough: Double,
    /** Noise floor at this frame, dBFS. */
    val floor: Double,
)

/**
 * Streams samples (any rate) into frames: every HOP samples, analyse the latest NFFT window.
 * Mirrors FrameAnalyser + roughness_proxy in distress_logic.py.
 */
class FrameAnalyzer(val fs: Int) {
    val hop: Int = Config.hopFor(fs)
    val hopSeconds: Double = hop.toDouble() / fs
    private val envSub: Int = Config.envSubFor(fs)

    private val nfft = Config.nfftFor(fs)
    private val fft = Dsp.Fft(nfft)
    private val hann = Dsp.hann(nfft)
    private val re = DoubleArray(nfft)
    private val im = DoubleArray(nfft)
    private val binHz = fs.toDouble() / nfft

    private val window = FloatArray(nfft)        // latest NFFT samples
    private val hopBuf = FloatArray(hop)
    private var hopFill = 0
    private var frameIdx = 0L

    // envelope ring (time-ordered on read)
    private val envRing = FloatArray(Config.ENV_N)
    private var envIdx = 0
    private val envHann = Dsp.hann(Config.ENV_N)
    private val envFft = Dsp.Fft(Config.ENV_N)
    private val envRe = DoubleArray(Config.ENV_N)
    private val envIm = DoubleArray(Config.ENV_N)

    // pitch
    private val dec = Config.pitchDecimationFor(fs)
    private val fsd = fs.toDouble() / dec
    private val nd = nfft / dec
    private val y = DoubleArray(nd)
    private val lagMin = (fsd / Config.F0_MAX).toInt()
    private val lagMax = minOf((fsd / Config.F0_MIN).toInt(), nd / 2)
    private val rr = DoubleArray(lagMax + 1)

    /** Tracked noise floor, dBFS; updated on unvoiced frames only. */
    var floor = Config.FLOOR_INIT
        private set

    /** Feed samples; returns the frames completed by this call. */
    fun process(x: FloatArray, count: Int = x.size): List<FrameFeatures> {
        val out = ArrayList<FrameFeatures>(1)
        var i = 0
        while (i < count) {
            val n = minOf(hop - hopFill, count - i)
            System.arraycopy(x, i, hopBuf, hopFill, n)
            hopFill += n; i += n
            if (hopFill == hop) {
                // slide window, append hop
                System.arraycopy(window, hop, window, 0, nfft - hop)
                System.arraycopy(hopBuf, 0, window, nfft - hop, hop)
                pushEnvelope(hopBuf)
                out.add(analyse())
                hopFill = 0
            }
        }
        return out
    }

    private var envFill = 0
    private var envAcc = 0.0
    private fun pushEnvelope(block: FloatArray) {
        for (v in block) {
            envAcc += v.toDouble() * v
            if (++envFill == envSub) {
                envRing[envIdx] = sqrt(envAcc / envSub).toFloat()
                envIdx = (envIdx + 1) % Config.ENV_N
                envFill = 0; envAcc = 0.0
            }
        }
    }

    private fun analyse(): FrameFeatures {
        val x = window
        // level
        var e = 0.0
        for (v in x) e += v.toDouble() * v
        val level = 20 * log10(sqrt(e / x.size) + 1e-9)

        // spectrum (power), bin 0 ignored
        for (k in 0 until nfft) { re[k] = x[k] * hann[k]; im[k] = 0.0 }
        fft.transform(re, im)
        var eLo = 0.0; var eHi = 0.0; var pk1 = 0.0; var pk2 = 0.0
        var gsum = 0.0; var asum = 0.0; var cnt = 0
        for (k in 1 until nfft / 2) {
            val p = re[k] * re[k] + im[k] * im[k]
            val f = k * binHz
            if (f >= 50 && f < 1000) eLo += p
            if (f >= 1000 && f < 5000) eHi += p
            if (f < 2000) { if (p > pk1) pk1 = p } else if (f < 5000) { if (p > pk2) pk2 = p }
            if (f >= 500 && f < 8000) { gsum += ln(p + 1e-20); asum += p; cnt++ }
        }
        val alpha = 10 * log10((eHi + 1e-20) / (eLo + 1e-20))
        val ham = 10 * log10((pk1 + 1e-20) / (pk2 + 1e-20))
        val flat = if (cnt > 0) exp(gsum / cnt) / (asum / cnt + 1e-20) else 0.0

        // f0: normalised autocorrelation on a 3:1 block-averaged copy, with an octave guard
        for (i in 0 until nd) {
            var s = 0.0
            for (j in 0 until dec) s += x[dec * i + j]
            y[i] = s / dec
        }
        val m = nd - lagMax
        var e0 = 0.0
        for (i in 0 until m) e0 += y[i] * y[i]
        var best = 0.0; var bestLag = 0
        for (lag in lagMin..lagMax) {
            var num = 0.0; var e1 = 0.0
            for (i in 0 until m) { num += y[i] * y[i + lag]; e1 += y[i + lag] * y[i + lag] }
            val r = num / (sqrt(e0 * e1) + 1e-12)
            rr[lag] = r
            if (r > best) { best = r; bestLag = lag }
        }
        for (lag in lagMin + 1 until lagMax) {
            if (rr[lag] >= Config.OCTAVE_GUARD * best && rr[lag] >= rr[lag - 1] && rr[lag] >= rr[lag + 1]) {
                bestLag = lag; best = rr[lag]; break
            }
        }
        val f0 = if (bestLag > 0) fsd / bestLag else 0.0
        val voiced = best > Config.VOICING_CORR && level > floor + Config.VOICING_SNR &&
                f0 >= Config.F0_MIN && f0 <= Config.F0_MAX

        val rough = roughnessProxy(if (voiced) f0 else 0.0)

        val t = frameIdx * hopSeconds
        val fr = FrameFeatures(frameIdx, t, level, if (voiced) f0 else 0.0, best, voiced, alpha, ham, flat, rough, floor)
        frameIdx++
        if (!voiced) floor = (1 - Config.FLOOR_ALPHA) * floor + Config.FLOOR_ALPHA * level
        return fr
    }

    /**
     * Share of amplitude-modulation energy in 30–150 Hz (the scream band), excluding the
     * modulation the pitch itself produces (f0 and 2·f0), so that a steady low-pitched voice
     * does not read as rough. Sub-harmonic modulation (f0/2, f0/3), the signature of a rough
     * or screamed voice, is kept.
     */
    private fun roughnessProxy(f0: Double): Double {
        val n = Config.ENV_N
        var mean = 0.0
        for (v in envRing) mean += v
        mean /= n
        if (mean < 1e-5) return 0.0
        var varSum = 0.0
        for (v in envRing) varSum += (v - mean) * (v - mean)
        val depth = sqrt(varSum / n) / mean
        if (depth < Config.ROUGH_MIN_DEPTH) return 0.0
        for (i in 0 until n) {
            envRe[i] = (envRing[(envIdx + i) % n] - mean) / mean * envHann[i]
            envIm[i] = 0.0
        }
        envFft.transform(envRe, envIm)
        val efs = fs.toDouble() / envSub
        val bw = 2.5 * efs / n
        var tot = 0.0; var band = 0.0
        for (k in 2..n / 2) {
            val p = envRe[k] * envRe[k] + envIm[k] * envIm[k]
            val f = k * efs / n
            tot += p
            val keep = f0 <= 0.0 || (abs(f - f0) > bw && abs(f - 2 * f0) > bw)
            if (f >= Config.ROUGH_BAND_LO && f <= Config.ROUGH_BAND_HI && keep) band += p
        }
        return if (tot > 0) band / tot else 0.0
    }
}
