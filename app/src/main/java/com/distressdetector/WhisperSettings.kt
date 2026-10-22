package com.distressdetector

import android.content.Context

internal enum class WhisperTask(val label: String, val nativeValue: String) {
    TRANSCRIBE("Transcribe", "transcribe"),
    TRANSLATE_TO_ENGLISH("Translate to English", "translate"),
    DETECT_LANGUAGE("Detect language", "detect-language"),
    AUTO_ROUTE("Auto route", "auto")
}

internal enum class WhisperModel(val label: String, val fileName: String) {
    TINY("Tiny", "ggml-tiny-q5_1.bin"),
    SMALL("Small", "ggml-small-q5_1.bin");

    companion object {
        fun fromFileName(fileName: String): WhisperModel = values().firstOrNull { it.fileName == fileName }
            ?: TINY
    }
}

internal enum class DetectionMode(val label: String) {
    WHISPER("Whisper transcription"),
    PSYCHOACOUSTIC("Psychoacoustic audio")
}

internal data class WhisperSettings(
    val task: WhisperTask,
    val language: String,
    val detectionMode: DetectionMode = DetectionMode.WHISPER
) {
    companion object {
        private const val PREFERENCES = "whisper_settings"
        private const val TASK = "task"
        private const val LANGUAGE = "language"
        private const val DETECTION_MODE = "detection_mode"

        fun load(context: Context): WhisperSettings {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val task = WhisperTask.values().firstOrNull { it.name == prefs.getString(TASK, null) }
                ?: WhisperTask.AUTO_ROUTE
            val detectionMode = prefs.getString(DETECTION_MODE, null)?.let { name ->
                DetectionMode.values().firstOrNull { it.name == name }
            } ?: DetectionMode.WHISPER
            return WhisperSettings(task, prefs.getString(LANGUAGE, "auto") ?: "auto", detectionMode)
        }

        fun save(context: Context, settings: WhisperSettings) {
            val normalized = settings.copy(task = WhisperTask.AUTO_ROUTE)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(TASK, normalized.task.name)
                .putString(LANGUAGE, normalized.language)
                .putString(DETECTION_MODE, normalized.detectionMode.name)
                .apply()
        }
    }
}

internal data class WhisperLanguage(val code: String, val name: String)
