package com.distressdetector

import java.util.Locale

internal object HelpPhraseDetector {
    private const val TRIGGER_WORD = "help"
    private const val REPETITIONS = 3
    private const val MAX_PHRASE_GAP_MS = 5_000L
    private val wordSeparators = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")

    fun newSession(): Session = Session()

    internal class Session {
        private var consecutiveHelpWords = 0
        private var lastHelpElapsedMs = 0L

        fun accept(transcript: String, elapsedRealtimeMs: Long): Int {
            if (lastHelpElapsedMs != 0L &&
                elapsedRealtimeMs - lastHelpElapsedMs > HelpPhraseDetector.MAX_PHRASE_GAP_MS
            ) {
                consecutiveHelpWords = 0
                lastHelpElapsedMs = 0L
            }

            val normalized = transcript
                .lowercase(Locale.ROOT)
                .replace(HelpPhraseDetector.wordSeparators, " ")
                .trim()
            if (normalized.isEmpty()) return 0

            var matches = 0
            for (word in normalized.split(HelpPhraseDetector.whitespace)) {
                if (word == HelpPhraseDetector.TRIGGER_WORD) {
                    consecutiveHelpWords++
                    lastHelpElapsedMs = elapsedRealtimeMs
                    if (consecutiveHelpWords >= HelpPhraseDetector.REPETITIONS) {
                        matches++
                        consecutiveHelpWords = 0
                        lastHelpElapsedMs = 0L
                    }
                } else {
                    consecutiveHelpWords = 0
                    lastHelpElapsedMs = 0L
                }
            }
            return matches
        }
    }
}
