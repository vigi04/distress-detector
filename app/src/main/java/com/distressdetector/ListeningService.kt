package com.distressdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.thecuriousfold.distress.core.AlertLevel
import com.thecuriousfold.distress.core.DistressEngine
import com.thecuriousfold.distress.core.EngineListener
import com.thecuriousfold.distress.core.EventRecord
import com.thecuriousfold.distress.core.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ListeningService : Service() {
    data class Snapshot(
        val active: Boolean = false,
        val message: String = "Listening is off. Tap Start to begin.",
        val transcript: String? = null,
        val detectedLanguageCode: String? = null,
        val detectedLanguageName: String? = null,
        val routeLabel: String? = null,
        val helpDetected: Boolean = false,
        val detectionCount: Int = 0,
        val psychoacousticScore: Int? = null,
        val psychoacousticDecision: String = "Waiting for audio events.",
        val psychoWordAlert: Boolean = false,
        val psychoScreamAlert: Boolean = false,
        val lastInferenceMs: Long? = null,
        val inferenceCount: Int = 0,
        val skippedSegments: Int = 0,
        val threadCount: Int? = null
    )

    interface Listener {
        fun onListeningStateChanged(snapshot: Snapshot)
    }

    inner class LocalBinder : Binder() {
        fun service(): ListeningService = this@ListeningService
    }

    private val binder = LocalBinder()
    private val processor = Executors.newSingleThreadExecutor()
    private val segments = ArrayBlockingQueue<CapturedSpeechSegment>(MAX_QUEUED_SEGMENTS)
    private val skippedSegmentCount = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerLock = Any()
    private val listeners = mutableSetOf<Listener>()

    @Volatile
    private var listening = false

    @Volatile
    private var sessionId = 0L

    @Volatile
    private var currentSnapshot = Snapshot()

    @Volatile
    private var microphoneCapture: MicrophoneCapture? = null

    private var whisperEngine: WhisperEngine? = null
    private var phraseSession = HelpPhraseDetector.newSession()
    @Volatile
    private var acousticEngine: DistressEngine? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }

        try {
            startForegroundWithNotification()
            beginListening()
        } catch (error: Exception) {
            stopListening("Listening could not start: ${error.localizedMessage ?: "check permissions"}")
        }
        return START_NOT_STICKY
    }

    fun addListener(listener: Listener) {
        synchronized(listenerLock) { listeners.add(listener) }
        mainHandler.post { listener.onListeningStateChanged(currentSnapshot) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listenerLock) { listeners.remove(listener) }
    }

    fun stopListening() {
        stopListening("Listening stopped. The microphone is off.")
    }

    override fun onDestroy() {
        listening = false
        sessionId++
        microphoneCapture?.stop()
        microphoneCapture = null
        acousticEngine?.stop()
        acousticEngine = null
        segments.clear()
        processor.execute {
            whisperEngine?.close()
            whisperEngine = null
        }
        processor.shutdown()
        super.onDestroy()
    }

    private fun beginListening() {
        if (listening) return
        listening = true
        val thisSession = ++sessionId
        segments.clear()
        skippedSegmentCount.set(0)
        inferenceCount = 0
        detectionCount = 0
        lastInferenceMs = null
        phraseSession = HelpPhraseDetector.newSession()
        acousticEngine?.stop()
        acousticEngine = null
        val settings = WhisperSettings.load(this)
        getSystemService(NotificationManager::class.java).cancel(SKIPPED_AUDIO_NOTIFICATION_ID)
        publish(Snapshot(active = true, message = when (settings.detectionMode) {
            DetectionMode.WHISPER -> "Preparing the on-device speech model."
            DetectionMode.PSYCHOACOUSTIC -> "Preparing the acoustic detector."
        }))

        processor.execute {
            try {
                when (settings.detectionMode) {
                    DetectionMode.WHISPER -> runWhisperSession(thisSession, settings)
                    DetectionMode.PSYCHOACOUSTIC -> runPsychoacousticSession(thisSession)
                }
            } catch (error: Exception) {
                whisperEngine?.close()
                whisperEngine = null
                acousticEngine?.stop()
                acousticEngine = null
                if (isCurrentSession(thisSession)) {
                    stopListening("Listening stopped: ${error.localizedMessage ?: "model setup failed"}")
                }
            }
        }
    }

    private fun runWhisperSession(thisSession: Long, settings: WhisperSettings) {
        val model = WhisperModel.fromFileName(getString(R.string.whisper_model_file))
        val engine = WhisperEngine(applicationContext, model)
        whisperEngine = engine
        Log.d(TAG, "Loading Whisper model=${model.label} (${model.fileName})")
        engine.load()
        if (!isCurrentSession(thisSession)) {
            engine.close()
            return
        }
        publish(currentSnapshot.copy(
            message = "Whisper ${model.label} is ready. Starting the microphone.",
            threadCount = engine.threadCount()
        ))
        val capture = MicrophoneCapture(
            onStarted = { publishListeningStarted(thisSession) },
            onAudioFrame = {},
            onSpeechSegment = { samples, capturedAt -> enqueueSpeechSegment(thisSession, samples, capturedAt) },
            onFailure = { message -> handleCaptureFailure(thisSession, message) }
        )
        microphoneCapture = capture
        capture.start()
        processSpeechSegments(engine, thisSession, settings)
        capture.stop()
        if (microphoneCapture === capture) microphoneCapture = null
        engine.close()
        if (whisperEngine === engine) whisperEngine = null
    }

    private fun runPsychoacousticSession(thisSession: Long) {
        val acoustic = DistressEngine(SpeechAudioFormat.SAMPLE_RATE, object : EngineListener {
            override fun onFrame(fr: com.thecuriousfold.distress.core.FrameFeatures) = Unit

            override fun onEvent(record: EventRecord) {
                if (!isCurrentSession(thisSession)) return
                val isAlert = record.decision.level == AlertLevel.ALERT
                publish(currentSnapshot.copy(
                    psychoacousticScore = (record.score.value * 100).toInt().coerceIn(0, 100),
                    psychoacousticDecision = "${record.event.shape.label}: ${record.decision.message}",
                    psychoWordAlert = isAlert && record.decision.path == Path.WORD,
                    psychoScreamAlert = isAlert && record.decision.path == Path.SCREAM
                ))
            }

            override fun onCalibrationFinished(
                success: Boolean,
                baseline: com.thecuriousfold.distress.core.Baseline
            ) = Unit
        })
        acousticEngine = acoustic
        publish(currentSnapshot.copy(message = "Psychoacoustic detector is ready. Starting the microphone."))
        val capture = MicrophoneCapture(
            onStarted = { publishListeningStarted(thisSession) },
            onAudioFrame = { frame -> if (isCurrentSession(thisSession)) acoustic.process(frame) },
            onFailure = { message -> handleCaptureFailure(thisSession, message) }
        )
        microphoneCapture = capture
        capture.start()
        while (isCurrentSession(thisSession)) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        capture.stop()
        acoustic.stop()
        if (microphoneCapture === capture) microphoneCapture = null
        if (acousticEngine === acoustic) acousticEngine = null
    }

    private fun publishListeningStarted(thisSession: Long) {
        if (isCurrentSession(thisSession)) publish(currentSnapshot.copy(
            active = true,
            message = "Listening on-device. Speech audio is not saved."
        ))
    }

    private fun processSpeechSegments(engine: WhisperEngine, thisSession: Long, settings: WhisperSettings) {
        while (isCurrentSession(thisSession)) {
            val capturedSegment = try {
                segments.poll(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: continue
            if (!isCurrentSession(thisSession)) return
            val samples = capturedSegment.samples

            publish(currentSnapshot.copy(message = "Processing a speech segment on this device."))
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            try {
                val autoDetection = if (settings.task == WhisperTask.AUTO_ROUTE) {
                    val detection = engine.process(samples, settings.copy(task = WhisperTask.DETECT_LANGUAGE))
                    Log.d(TAG, "Auto route detected language=${detection.languageCode} (${detection.languageName})")
                    detection
                } else null

                val route = if (autoDetection != null) LanguageRouter.route(autoDetection.languageCode) else settings.task
                val result = if (autoDetection != null) {
                    Log.d(TAG, "Auto route decision: language=${autoDetection.languageCode} (${autoDetection.languageName}) -> ${route.name}")
                    engine.process(samples, settings.copy(task = route))
                } else {
                    val directResult = engine.process(samples, settings)
                    Log.d(TAG, "Direct path: task=${settings.task.name}, language=${directResult.languageCode} (${directResult.languageName}), text=${directResult.text.take(200)}")
                    directResult
                }
                val transcript = result.text.trim()
                val detectedLanguageName = autoDetection?.languageName ?: result.languageName
                val detectedLanguageCode = autoDetection?.languageCode ?: result.languageCode
                val routeLabel = if (autoDetection != null) route.name else settings.task.name
                val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
                if (!isCurrentSession(thisSession)) return
                inferenceCount++
                lastInferenceMs = inferenceMs
                if (settings.task == WhisperTask.DETECT_LANGUAGE) {
                    val languageName = result.languageName
                    publish(currentSnapshot.copy(
                        message = "Whisper detected $languageName.",
                        transcript = "Detected language: $languageName (${result.languageCode})",
                        lastInferenceMs = inferenceMs,
                        inferenceCount = inferenceCount,
                        skippedSegments = skippedSegmentCount.get()
                    ))
                    continue
                }
                if (transcript.isEmpty()) {
                    publish(currentSnapshot.copy(
                        message = if (autoDetection != null) {
                            "Detected ${detectedLanguageName} (${detectedLanguageCode}) and routed to ${routeLabel.lowercase()}."
                        } else {
                            "Speech segment processed locally; no words recognized."
                        },
                        transcript = "No words recognized.",
                        detectedLanguageCode = detectedLanguageCode,
                        detectedLanguageName = detectedLanguageName,
                        routeLabel = routeLabel,
                        lastInferenceMs = inferenceMs,
                        inferenceCount = inferenceCount,
                        skippedSegments = skippedSegmentCount.get()
                    ))
                    continue
                }

                val alertTextIsEnglish = settings.task == WhisperTask.TRANSLATE_TO_ENGLISH || result.languageCode == "en"
                val matches = if (alertTextIsEnglish) {
                    phraseSession.accept(transcript, capturedSegment.capturedAtElapsedMs)
                } else 0
                detectionCount += matches
                val detectedInSession = currentSnapshot.helpDetected || matches > 0
                val message = when {
                    matches > 0 -> "Repeated help phrase detected ($matches match${if (matches == 1) "" else "es"}). Listening continues."
                    detectedInSession -> "Repeated help phrase detected earlier. Listening continues."
                    else -> when (settings.task) {
                        WhisperTask.TRANSLATE_TO_ENGLISH -> "Speech translated to English on this device. Listening for more."
                        WhisperTask.TRANSCRIBE -> if (result.languageCode == "en") {
                            "Speech transcribed locally. Listening for more."
                        } else {
                            "${result.languageName} speech transcribed. The help phrase alert checks English text."
                        }
                        WhisperTask.DETECT_LANGUAGE -> "Language detection is active."
                        WhisperTask.AUTO_ROUTE -> if (result.languageCode == "en") {
                            "English speech transcribed after auto routing. Listening for more."
                        } else {
                            "Non-English speech translated to English after auto routing. Listening for more."
                        }
                    }
                }
                publish(currentSnapshot.copy(
                    active = true,
                    message = if (autoDetection != null) {
                        "Detected ${detectedLanguageName} (${detectedLanguageCode}) and routed to ${routeLabel.lowercase()}. $message"
                    } else message,
                    transcript = transcript,
                    detectedLanguageCode = detectedLanguageCode,
                    detectedLanguageName = detectedLanguageName,
                    routeLabel = routeLabel,
                    helpDetected = detectedInSession,
                    detectionCount = detectionCount,
                    lastInferenceMs = inferenceMs,
                    inferenceCount = inferenceCount,
                    skippedSegments = skippedSegmentCount.get()
                ))
                if (matches > 0) {
                    repeat(matches) { showHelpDetectedNotification() }
                }
            } catch (error: Exception) {
                val inferenceMs = (SystemClock.elapsedRealtime() - inferenceStartedAt).coerceAtLeast(0)
                if (isCurrentSession(thisSession)) {
                    inferenceCount++
                    lastInferenceMs = inferenceMs
                }
                publish(currentSnapshot.copy(
                    active = true,
                    message = "A speech segment could not be processed: ${error.localizedMessage ?: "unknown error"}",
                    lastInferenceMs = inferenceMs,
                    inferenceCount = inferenceCount,
                    skippedSegments = skippedSegmentCount.get()
                ))
            }
        }
    }

    private fun enqueueSpeechSegment(thisSession: Long, samples: FloatArray, capturedAtElapsedMs: Long) {
        if (!isCurrentSession(thisSession)) return
        if (!segments.offer(CapturedSpeechSegment(samples, capturedAtElapsedMs))) {
            val skippedCount = skippedSegmentCount.incrementAndGet()
            publish(currentSnapshot.copy(
                message = "Whisper is catching up. $skippedCount speech segment${if (skippedCount == 1) " was" else "s were"} skipped.",
                skippedSegments = skippedCount
            ))
            updateForegroundNotification(currentSnapshot.message)
            showSkippedAudioNotification(skippedCount)
        }
    }

    private fun handleCaptureFailure(thisSession: Long, message: String) {
        if (isCurrentSession(thisSession)) stopListening("Microphone capture stopped: $message")
    }

    private fun stopListening(message: String) {
        sessionId++
        listening = false
        microphoneCapture?.stop()
        microphoneCapture = null
        acousticEngine?.stop()
        acousticEngine = null
        segments.clear()

        publish(currentSnapshot.copy(active = false, message = message))
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun isCurrentSession(candidate: Long): Boolean = listening && candidate == sessionId

    private fun publish(snapshot: Snapshot) {
        currentSnapshot = snapshot
        if (foregroundStarted && snapshot.active) updateForegroundNotification(snapshot.message)
        mainHandler.post {
            val currentListeners = synchronized(listenerLock) { listeners.toList() }
            currentListeners.forEach { it.onListeningStateChanged(snapshot) }
        }
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "On-device listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Distress Detector is listening on this phone."
                setShowBadge(false)
            })
            manager.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL_ID,
                "Help phrase alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sounds and vibrates when the repeated help phrase is transcribed."
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 180, 650)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            manager.createNotificationChannel(NotificationChannel(
                SKIP_CHANNEL_ID,
                "Skipped audio updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silently reports speech segments skipped because transcription fell behind."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }

        val notification = buildNotification("Preparing the on-device speech model.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateForegroundNotification(message: String) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun showHelpDetectedNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(HELP_ALERT_NOTIFICATION_ID)
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        manager.notify(
            HELP_ALERT_NOTIFICATION_ID,
            builder.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Help phrase detected")
                .setContentText("Recognized: help, help, help")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        )
    }

    private fun showSkippedAudioNotification(skippedCount: Int) {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val countLabel = "$skippedCount speech segment${if (skippedCount == 1) " was" else "s were"} skipped."
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, SKIP_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        getSystemService(NotificationManager::class.java).notify(
            SKIPPED_AUDIO_NOTIFICATION_ID,
            builder.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Listening may have missed speech")
                .setContentText(countLabel)
                .setContentIntent(openApp)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        )
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_SERVICE_REQUEST,
            Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Distress Detector")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.distressdetector.action.START_LISTENING"
        const val ACTION_STOP = "com.distressdetector.action.STOP_LISTENING"
        private const val TAG = "DistressDetector"

        private const val NOTIFICATION_CHANNEL_ID = "on_device_listening"
        private const val NOTIFICATION_ID = 8401
        private const val OPEN_APP_REQUEST = 8402
        private const val STOP_SERVICE_REQUEST = 8403
        private const val SKIPPED_AUDIO_NOTIFICATION_ID = 8404
        private const val HELP_ALERT_NOTIFICATION_ID = 8405
        private const val MAX_QUEUED_SEGMENTS = 2
        private const val ALERT_CHANNEL_ID = "help_phrase_alerts_v1"
        private const val SKIP_CHANNEL_ID = "audio_skip_updates_v1"
    }

    private data class CapturedSpeechSegment(
        val samples: FloatArray,
        val capturedAtElapsedMs: Long
    )

    @Volatile
    private var lastInferenceMs: Long? = null

    @Volatile
    private var inferenceCount = 0

    @Volatile
    private var detectionCount = 0

}
