package com.thecuriousfold.distress.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 16-bit PCM mono WAV writer. */
object WavWriter {
    fun write(out: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val h = ByteArrayOutputStream(44)
        fun str(s: String) = h.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Int) { h.write(v and 0xff); h.write((v shr 8) and 0xff); h.write((v shr 16) and 0xff); h.write((v shr 24) and 0xff) }
        fun u16(v: Int) { h.write(v and 0xff); h.write((v shr 8) and 0xff) }
        str("RIFF"); u32(36 + dataBytes); str("WAVE")
        str("fmt "); u32(16); u16(1); u16(1); u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16)
        str("data"); u32(dataBytes)
        out.write(h.toByteArray())
        val buf = ByteArray(dataBytes)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[2 * i] = (v and 0xff).toByte()
            buf[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        out.write(buf)
        out.flush()
    }
}

/** CSV export with the same columns as the browser prototype — the input to stage 2. */
object CsvExport {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    val columns = listOf(
        "id", "time", "label", "trigger", "dur", "f0hz", "f0st", "f0range", "f0slope", "jitter", "level", "levelMax",
        "alpha", "ham", "rough", "burst", "stop", "helpScore", "helpLike", "shape", "distress",
        "z_rough", "z_f0st", "z_f0range", "z_alpha", "z_ham", "z_jitter", "z_level", "z_dur", "baseline",
    )

    fun row(r: EventRecord): String {
        val e = r.event; val s = r.score
        fun n(v: Double) = String.format(Locale.US, "%.4f", v)
        return listOf(
            e.id.toString(), iso.format(Date(r.wallClock)), r.label, r.trigger,
            n(e.dur), n(e.f0Hz), n(e.f0St), n(e.f0Range), n(e.f0Slope), n(e.jitter), n(e.level), n(e.levelMax),
            n(e.alpha), n(e.ham), n(e.rough), if (e.burst) "1" else "0", if (e.stop) "1" else "0",
            n(e.helpScore), if (e.helpLike) "1" else "0", e.shape.label, n(s.value),
            *Feature.entries.map { n(s.z[it.ordinal]) }.toTypedArray(), r.baselineKind,
        ).joinToString(",")
    }

    /** Oldest first. */
    fun build(records: List<EventRecord>): String =
        buildString { appendLine(columns.joinToString(",")); for (r in records.sortedBy { it.event.id }) appendLine(row(r)) }

    fun stamp(ms: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))
}
