package com.distressdetector

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock

internal class MicrophoneCapture(
    private val onStarted: () -> Unit,
    private val onAudioFrame: (FloatArray) -> Unit,
    private val onSpeechSegment: ((FloatArray, Long) -> Unit)? = null,
    private val onFailure: (String) -> Unit
) {
    @Volatile
    private var stopping = false

    @Volatile
    private var recorder: AudioRecord? = null

    private var captureThread: Thread? = null

    fun start() {
        captureThread = Thread(::captureLoop, "distress-detector-microphone").apply { start() }
    }

    fun stop() {
        stopping = true
        recorder?.let { audioRecord ->
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { audioRecord.stop() }
            }
        }
        if (Thread.currentThread() !== captureThread) {
            runCatching { captureThread?.join(1_000) }
        }
    }

    private fun captureLoop() {
        var audioRecord: AudioRecord? = null
        try {
            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SpeechAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimumBufferSize > 0) { "This device cannot start microphone recording." }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SpeechAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBufferSize, SpeechAudioFormat.FRAME_SAMPLES * Short.SIZE_BYTES * 4)
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "The microphone could not be opened."
            }
            recorder = audioRecord
            if (stopping) return

            audioRecord.startRecording()
            onStarted()

            val detector = if (onSpeechSegment != null) EnergyVoiceActivityDetector() else null
            val pcmFrame = ShortArray(SpeechAudioFormat.FRAME_SAMPLES)
            while (!stopping) {
                val count = audioRecord.read(
                    pcmFrame,
                    0,
                    pcmFrame.size,
                    AudioRecord.READ_BLOCKING
                )
                if (stopping) break
                check(count > 0) { "Microphone capture stopped unexpectedly." }

                val frame = FloatArray(SpeechAudioFormat.FRAME_SAMPLES)
                for (index in 0 until minOf(count, frame.size)) {
                    frame[index] = pcmFrame[index] / 32768.0f
                }
                onAudioFrame(frame)
                detector?.accept(frame)?.let { segment ->
                    onSpeechSegment?.invoke(segment, SystemClock.elapsedRealtime())
                }
            }
        } catch (error: Exception) {
            if (!stopping) onFailure(error.localizedMessage ?: "Microphone capture failed.")
        } finally {
            recorder = null
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { record.stop() }
                }
                record.release()
            }
        }
    }
}
