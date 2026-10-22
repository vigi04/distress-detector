package com.distressdetector

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRouterTest {
    @Test
    fun englishUsesTranscription() {
        assertEquals(WhisperTask.TRANSCRIBE, LanguageRouter.route("en"))
    }

    @Test
    fun nonEnglishUsesTranslation() {
        assertEquals(WhisperTask.TRANSLATE_TO_ENGLISH, LanguageRouter.route("es"))
    }
}
