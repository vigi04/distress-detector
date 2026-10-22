package com.distressdetector

internal object LanguageRouter {
    fun route(languageCode: String): WhisperTask {
        val normalized = languageCode.trim().lowercase()
        return if (normalized == "en") WhisperTask.TRANSCRIBE else WhisperTask.TRANSLATE_TO_ENGLISH
    }
}
