package com.thecuriousfold.distress.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** Small, dependency-free helpers. Written to port to C without surprises. */
object Dsp {
    /** Pitch in semitones above 27.5 Hz (the eGeMAPS convention). */
    fun semitone(fHz: Double): Double = 12.0 * ln(fHz / 27.5) / ln(2.0)
    fun semitoneToHz(st: Double): Double = 27.5 * 2.0.pow(st / 12.0)
    fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
    fun clamp(x: Double, a: Double, b: Double): Double = maxOf(a, minOf(b, x))
    fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
    fun mean(a: DoubleArray): Double = if (a.isEmpty()) 0.0 else a.sum() / a.size
    fun sd(a: DoubleArray, m: Double): Double {
        if (a.isEmpty()) return 0.0
        var s = 0.0
        for (v in a) s += (v - m) * (v - m)
        return kotlin.math.sqrt(s / a.size)
    }

    /** Linear-regression slope of y against t. */
    fun linSlope(t: DoubleArray, y: DoubleArray): Double {
        val n = t.size
        if (n < 2) return 0.0
        val tm = mean(t); val ym = mean(y)
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (t[i] - tm) * (y[i] - ym); den += (t[i] - tm) * (t[i] - tm) }
        return if (den > 0) num / den else 0.0
    }

    /** In-place iterative radix-2 FFT; re/im must have power-of-two length n. */
    class Fft(val n: Int) {
        init { require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two" } }
        private val cosT = DoubleArray(n / 2) { cos(-2.0 * PI * it / n) }
        private val sinT = DoubleArray(n / 2) { sin(-2.0 * PI * it / n) }
        private val rev = IntArray(n).also { r ->
            var bits = 0
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) {
                var x = i; var v = 0
                for (b in 0 until bits) { v = (v shl 1) or (x and 1); x = x shr 1 }
                r[i] = v
            }
        }

        fun transform(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val step = n / len
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in 0 until half) {
                        val wr = cosT[k]; val wi = sinT[k]
                        val a = i + j; val b = a + half
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        k += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
