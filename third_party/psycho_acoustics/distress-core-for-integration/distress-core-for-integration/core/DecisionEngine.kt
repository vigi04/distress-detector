package com.thecuriousfold.distress.core

enum class AlertLevel { STANDBY, WORD_ONLY, ALERT }
enum class Path { NONE, WORD, SCREAM }

class Decision(
    val level: AlertLevel,
    val path: Path,
    /** Word-shaped events in the window (including this one) and their mean distress. */
    val wordCount: Int,
    val wordMeanDistress: Double,
    val t: Double,
    val message: String,
)

/**
 * Two-path rule — Detector._decide() in the reference, extended so that "bachao"-shaped
 * events count on the word path alongside "help"-shaped ones (mixed is fine).
 *
 *  Scream path: one event ≥ 0.3 s, distress ≥ 0.85, roughness ≥ +2σ  → ALERT
 *  Word path:   ≥ 3 word-shaped events within 6 s; mean distress ≥ 0.5 → ALERT,
 *               below → WORD_ONLY (the word was heard, the tone reads calm; no alert)
 */
class DecisionEngine {
    private class Hit(val id: Long, val tEnd: Double, val distress: Double)
    private val recent = ArrayList<Hit>()

    fun evaluate(ev: SpeechEvent, score: Score): Decision {
        val now = ev.tEnd
        recent.removeAll { now - it.tEnd > Config.WINDOW_S }
        ev.absorbsEventId?.let { id -> recent.removeAll { it.id == id } }
        if (ev.wordShaped) recent.add(Hit(ev.id, now, score.value))
        val n = recent.size
        val md = if (n > 0) recent.sumOf { it.distress } / n else 0.0

        if (ev.dur >= Config.SCREAM_MIN_DUR && score.value >= Config.SCREAM_DISTRESS && score.roughZ >= Config.SCREAM_ROUGH_Z) {
            return Decision(AlertLevel.ALERT, Path.SCREAM, n, md, now,
                "Scream path: %.2f s voiced, distress %.2f, roughness +%.1fσ".format(ev.dur, score.value, score.roughZ))
        }
        if (n >= Config.N_REPEAT) {
            return if (md >= Config.WORD_DISTRESS) {
                Decision(AlertLevel.ALERT, Path.WORD, n, md, now,
                    "Word path: %d× word-shaped in %.0f s, mean distress %.2f".format(n, Config.WINDOW_S, md))
            } else {
                Decision(AlertLevel.WORD_ONLY, Path.NONE, n, md, now,
                    "%d× word-shaped in %.0f s, but tone reads calm (mean distress %.2f). No alert.".format(n, Config.WINDOW_S, md))
            }
        }
        return Decision(AlertLevel.STANDBY, Path.NONE, n, md, now,
            if (n > 0) "%d of %d word-shaped events in the window".format(n, Config.N_REPEAT) else "Listening.")
    }

    fun reset() = recent.clear()
}
