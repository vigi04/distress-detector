package com.distressdetector

import android.content.Context
import java.io.File

internal class WhisperEngine(
    context: Context,
    private val model: WhisperModel
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var nativeContext = 0L

    fun load() {
        if (nativeContext != 0L) return

        val modelFileName = model.fileName
        val modelFile = File(appContext.filesDir, modelFileName)
        if (!modelFile.exists()) {
            val temporaryFile = File(appContext.filesDir, "$modelFileName.tmp")
            temporaryFile.delete()
            appContext.assets.open(modelFileName).use { input ->
                temporaryFile.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporaryFile.renameTo(modelFile)) { "Could not prepare the on-device model file." }
        }

        nativeContext = nativeInit(modelFile.absolutePath)
        check(nativeContext != 0L) { "Whisper ${model.label} could not be initialized." }
    }

    data class Result(val languageCode: String, val languageName: String, val text: String)

    fun supportedLanguages(): List<WhisperLanguage> = nativeGetLanguages().map { entry ->
        val separator = entry.indexOf('\t')
        check(separator > 0) { "Whisper returned an invalid language entry." }
        WhisperLanguage(entry.substring(0, separator), entry.substring(separator + 1))
    }

    fun threadCount(): Int = nativeGetThreadCount()

    fun process(samples: FloatArray, settings: WhisperSettings): Result {
        check(nativeContext != 0L) { "Whisper ${model.label} has not been loaded." }
        val effectiveTask = when (settings.task) {
            WhisperTask.AUTO_ROUTE -> WhisperTask.DETECT_LANGUAGE
            else -> settings.task
        }
        val language = if (effectiveTask == WhisperTask.DETECT_LANGUAGE) "auto" else settings.language
        val result = nativeProcess(nativeContext, samples, effectiveTask.nativeValue, language)
        val separator = result.indexOf('\n')
        check(separator >= 0) { "Whisper returned an invalid result." }
        val metadata = result.substring(0, separator).split('\t', limit = 2)
        check(metadata.size == 2) { "Whisper returned invalid language metadata." }
        return Result(metadata[0], metadata[1], result.substring(separator + 1))
    }

    override fun close() {
        if (nativeContext != 0L) {
            nativeFree(nativeContext)
            nativeContext = 0L
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeGetLanguages(): Array<String>
    private external fun nativeGetThreadCount(): Int
    private external fun nativeProcess(contextHandle: Long, samples: FloatArray, task: String, language: String): String
    private external fun nativeFree(contextHandle: Long)

    private companion object {
        init {
            System.loadLibrary("distress_whisper")
        }
    }
}
