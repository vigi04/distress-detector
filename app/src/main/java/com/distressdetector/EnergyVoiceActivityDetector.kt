package com.distressdetector

import kotlin.math.sqrt

internal object SpeechAudioFormat {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MILLIS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MILLIS / 1_000
}

internal class EnergyVoiceActivityDetector {
    private val preRoll = ArrayDeque<FloatArray>()
    private val utterance = ArrayList<FloatArray>()
    private var candidateSpeechFrames = 0
    private var silenceFrames = 0
    private var speechStarted = false

    fun accept(frame: FloatArray): FloatArray? {
        require(frame.size == SpeechAudioFormat.FRAME_SAMPLES)
        val copy = frame.copyOf()
        val hasSpeechEnergy = rms(copy) >= RMS_THRESHOLD

        if (!speechStarted) {
            preRoll.addLast(copy)
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            candidateSpeechFrames = if (hasSpeechEnergy) candidateSpeechFrames + 1 else 0
            if (candidateSpeechFrames >= START_FRAMES) {
                speechStarted = true
                utterance.addAll(preRoll)
                preRoll.clear()
                silenceFrames = 0
            }
            return null
        }

        utterance.add(copy)
        if (hasSpeechEnergy) {
            silenceFrames = 0
        } else {
            silenceFrames++
        }

        if (silenceFrames >= END_SILENCE_FRAMES || utterance.size >= MAX_UTTERANCE_FRAMES) {
            return finishUtterance()
        }
        return null
    }

    private fun finishUtterance(): FloatArray? {
        val end = (utterance.size - silenceFrames.coerceAtMost(utterance.size)).coerceAtLeast(0)
        val length = utterance.take(end).sumOf { it.size }
        val result = if (length >= MIN_UTTERANCE_SAMPLES) {
            FloatArray(length).also { output ->
                var offset = 0
                for (index in 0 until end) {
                    val frame = utterance[index]
                    frame.copyInto(output, offset)
                    offset += frame.size
                }
            }
        } else {
            null
        }
        utterance.clear()
        preRoll.clear()
        candidateSpeechFrames = 0
        silenceFrames = 0
        speechStarted = false
        return result
    }

    private fun rms(samples: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in samples) sumSquares += sample * sample
        return sqrt(sumSquares / samples.size).toFloat()
    }

    private companion object {
        const val RMS_THRESHOLD = 0.0125f
        const val START_FRAMES = 3
        const val PRE_ROLL_FRAMES = 12
        const val END_SILENCE_FRAMES = 50
        const val MAX_UTTERANCE_FRAMES = 600
        const val MIN_UTTERANCE_SAMPLES = SpeechAudioFormat.SAMPLE_RATE / 5
    }
}
