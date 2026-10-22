package com.distressdetector

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter

class MainActivity : Activity() {
    private lateinit var actionButton: Button
    private lateinit var permissionStatus: TextView
    private lateinit var permissionDot: View
    private lateinit var modelStatus: TextView
    private lateinit var metricsStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var transcriptPanel: View
    private lateinit var transcriptText: TextView
    private lateinit var detectionStatus: TextView
    private lateinit var acousticStatus: TextView
    private lateinit var detectionModeSpinner: Spinner
    private lateinit var settingsHint: TextView

    private var listeningService: ListeningService? = null
    private var serviceBound = false
    private var listeningSnapshot = ListeningService.Snapshot()
    private lateinit var whisperSettings: WhisperSettings
    private val serviceListener = object : ListeningService.Listener {
        override fun onListeningStateChanged(snapshot: ListeningService.Snapshot) {
            listeningSnapshot = snapshot
            renderListeningState()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val serviceBinder = binder as? ListeningService.LocalBinder ?: return
            listeningService = serviceBinder.service()
            serviceBound = true
            listeningService?.addListener(serviceListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            listeningService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        whisperSettings = WhisperSettings.load(this).copy(task = WhisperTask.AUTO_ROUTE)
        window.statusBarColor = color(Colors.canvas)
        window.navigationBarColor = color(Colors.canvas)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        renderScreen()
        refreshPermissionState()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ListeningService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (serviceBound) {
            listeningService?.removeListener(serviceListener)
            unbindService(serviceConnection)
            listeningService = null
            serviceBound = false
        }
        super.onStop()
    }

    @Deprecated("Permission results are delivered by the platform callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            MICROPHONE_REQUEST -> {
                refreshPermissionState()
                if (granted) requestNotificationsAndStart()
                else renderPermissionMessage("Microphone access is required to listen. It remains off until you tap Start.")
            }
            NOTIFICATION_REQUEST -> {
                if (granted) startListeningService()
                else renderPermissionMessage("Notifications are needed to show the active listening control.")
            }
        }
    }

    private fun renderScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(26))
            setBackgroundColor(color(Colors.canvas))
        }

        page.addView(buildStatusCard(), matchWidth())
        page.addView(buildSetupSection(), matchWidth().withTop(dp(16)))
        page.addView(buildTranscriptCard(), matchWidth().withTop(dp(16)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(color(Colors.canvas))
            addView(page)
        }
        setContentView(scroll)
    }

    private fun buildBrandRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val mark = TextView(this@MainActivity).apply {
            text = "DD"
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(color(Colors.white))
            background = rounded(Colors.blue, 14)
        }
        addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)))

        val name = TextView(this@MainActivity).apply {
            text = "DISTRESS DETECTOR\nON-DEVICE VOICE SAFETY"
            textSize = 12f
            letterSpacing = 0.025f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(Colors.ink))
            setPadding(dp(12), 0, 0, 0)
        }
        addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this@MainActivity).apply {
            text = "LOCAL\nPROCESSING"
            textSize = 9f
            letterSpacing = 0.05f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(Colors.greenInk))
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(Colors.greenPale, 12)
        }
        addView(badge)
    }

    private fun buildStatusCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Colors.white, 18)

        permissionDot = View(this@MainActivity).apply {
            background = rounded(Colors.grayDot, 50)
            contentDescription = "Microphone permission status indicator"
        }
        addView(permissionDot, LinearLayout.LayoutParams(dp(12), dp(12)))

        val textGroup = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        val label = TextView(this@MainActivity).apply {
            text = "MICROPHONE STATUS"
            textSize = 9f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(Colors.muted))
        }
        textGroup.addView(label, matchWidth())
        permissionStatus = TextView(this@MainActivity).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(Colors.ink))
            setPadding(0, dp(4), 0, 0)
            contentDescription = "Microphone access status"
        }
        textGroup.addView(permissionStatus, matchWidth())
        addView(textGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val chevron = TextView(this@MainActivity).apply {
            text = "›"
            textSize = 25f
            setTextColor(color(Colors.muted))
            gravity = Gravity.CENTER
        }
        addView(chevron, LinearLayout.LayoutParams(dp(24), dp(32)))
    }

    private fun buildSetupSection(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Colors.white, 20)
        setPadding(dp(18), dp(18), dp(18), dp(18))

        val title = TextView(this@MainActivity).apply {
            text = "Listening"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(color(Colors.ink))
        }
        addView(title, matchWidth())

        addView(sectionLabel("DETECTION MODE"), matchWidth().withTop(dp(12)))
        detectionModeSpinner = Spinner(this@MainActivity).apply {
            adapter = spinnerAdapter(DetectionMode.values().map { it.label })
            setSelection(DetectionMode.values().indexOf(whisperSettings.detectionMode))
            background = rounded(Colors.white, 14)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            elevation = dp(1).toFloat()
        }
        addView(detectionModeSpinner, matchWidth().withTop(dp(7)))

        settingsHint = TextView(this@MainActivity).apply {
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1.12f)
            setTextColor(color(Colors.muted))
            setPadding(0, dp(8), 0, dp(10))
        }
        addView(settingsHint, matchWidth())

        detectionModeSpinner.onItemSelectedListener = selectionListener {
            val mode = DetectionMode.values()[detectionModeSpinner.selectedItemPosition]
            whisperSettings = whisperSettings.copy(detectionMode = mode)
            updateSettings()
        }
        updateSettings()

        actionButton = Button(this@MainActivity).apply {
            text = "Start listening"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(Colors.white))
            background = rounded(Colors.action, 14)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minHeight = dp(54)
            stateListAnimator = null
            setOnClickListener { onActionButtonPressed() }
        }
        addView(actionButton, matchWidth().withTop(dp(12)))

        modelStatus = TextView(this@MainActivity).apply {
            text = "Listening is off. Your microphone is not active."
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1.12f)
            gravity = Gravity.CENTER
            setTextColor(color(Colors.muted))
            setPadding(dp(6), dp(12), dp(6), 0)
        }
        addView(modelStatus, matchWidth())

        runtimeStatus = TextView(this@MainActivity).apply {
            text = "Threads: 1"
            textSize = 10f
            letterSpacing = 0.08f
            setLineSpacing(dp(2).toFloat(), 1.12f)
            gravity = Gravity.CENTER
            setTextColor(color(Colors.muted))
            setPadding(dp(6), dp(0), dp(6), dp(6))
        }
        addView(runtimeStatus, matchWidth())

        metricsStatus = TextView(this@MainActivity).apply {
            text = "Inference: waiting for first segment  ·  Segments: 0  ·  Skipped: 0"
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1.12f)
            gravity = Gravity.CENTER
            setTextColor(color(Colors.muted))
            setPadding(dp(6), dp(8), dp(6), 0)
        }
        addView(metricsStatus, matchWidth())
    }

    private fun onActionButtonPressed() {
        if (listeningSnapshot.active) {
            listeningService?.stopListening()
                ?: stopService(Intent(this, ListeningService::class.java))
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        requestNotificationsAndStart()
    }

    private fun requestNotificationsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        startListeningService()
    }

    private fun startListeningService() {
        val intent = Intent(this, ListeningService::class.java)
            .setAction(ListeningService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            renderPermissionMessage("Starting on-device listening...")
        } catch (error: Exception) {
            renderPermissionMessage("Listening could not start: ${error.localizedMessage ?: "check permissions"}")
        }
    }

    private fun renderPermissionMessage(message: String) {
        listeningSnapshot = listeningSnapshot.copy(active = false, message = message)
        renderListeningState()
    }

    private fun renderListeningState() {
        modelStatus.text = when {
            listeningSnapshot.active -> "Listening on-device"
            listeningSnapshot.message.contains("could not start", ignoreCase = true) -> "Setup issue"
            else -> "Ready to listen"
        }
        val runtimeThreads = listeningSnapshot.threadCount ?: 1
        runtimeStatus.text = "Threads: $runtimeThreads"
        val inferenceTime = listeningSnapshot.lastInferenceMs?.let { "$it ms" } ?: "waiting"
        val whisperMode = whisperSettings.detectionMode == DetectionMode.WHISPER
        runtimeStatus.visibility = if (whisperMode) View.VISIBLE else View.GONE
        metricsStatus.visibility = if (whisperMode) View.VISIBLE else View.GONE
        metricsStatus.text = "Last inference: $inferenceTime  ·  Runs: ${listeningSnapshot.inferenceCount}  ·  Skipped: ${listeningSnapshot.skippedSegments}"
        actionButton.text = if (listeningSnapshot.active) "Stop listening" else "Start listening"
        actionButton.isEnabled = true
        actionButton.alpha = 1f
        detectionModeSpinner.isEnabled = !listeningSnapshot.active

        val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        permissionStatus.text = when {
            listeningSnapshot.active -> "Listening | user started"
            microphoneGranted -> "Microphone off | access ready"
            else -> "Microphone access not granted"
        }
        permissionStatus.setTextColor(color(if (listeningSnapshot.active) Colors.greenInk else Colors.ink))
        permissionDot.background = rounded(
            if (listeningSnapshot.active) Colors.green else Colors.grayDot,
            50
        )

        val transcript = listeningSnapshot.transcript.takeIf { whisperMode }
        val psychoScore = listeningSnapshot.psychoacousticScore.takeIf {
            whisperSettings.detectionMode == DetectionMode.PSYCHOACOUSTIC
        }
        if (transcript == null && psychoScore == null) {
            transcriptPanel.visibility = View.GONE
        } else {
            transcriptText.text = transcript ?: "Analyzing microphone audio directly; no transcript is produced."
            if (transcript == null) {
                detectionStatus.visibility = View.GONE
            } else {
                detectionStatus.visibility = View.VISIBLE
                val detectedLanguageName = listeningSnapshot.detectedLanguageName
                val detectedLanguageCode = listeningSnapshot.detectedLanguageCode
                val routeLabel = listeningSnapshot.routeLabel
                val languageText = if (detectedLanguageName != null && detectedLanguageCode != null) {
                    detectedLanguageName
                } else routeLabel ?: "Unknown"
                val actionText = when (routeLabel?.lowercase()) {
                    "transcribe" -> "Transcribe"
                    "translate_to_english" -> "Translate"
                    else -> "Transcribe"
                }
                detectionStatus.text = "Language: $languageText | Action: $actionText"
                detectionStatus.setTextColor(color(if (listeningSnapshot.helpDetected) Colors.greenInk else Colors.muted))
                detectionStatus.background = rounded(
                    if (listeningSnapshot.helpDetected) Colors.greenPale else Colors.canvas,
                    12
                )
            }

            val score = psychoScore
            if (score == null) {
                acousticStatus.visibility = View.GONE
            } else {
                acousticStatus.visibility = View.VISIBLE
                acousticStatus.text = "Psychoacoustic detector: $score/100 • ${listeningSnapshot.psychoacousticDecision}"
                acousticStatus.setTextColor(color(if (listeningSnapshot.psychoWordAlert || listeningSnapshot.psychoScreamAlert) Colors.coral else Colors.muted))
            }
            transcriptPanel.visibility = View.VISIBLE
        }
    }
    private fun buildTranscriptCard(): View = LinearLayout(this).apply {
        transcriptPanel = this
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Colors.white, 20)
        visibility = View.GONE

        val label = TextView(this@MainActivity).apply {
            text = "Latest detection"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(Colors.muted))
        }
        addView(label, matchWidth())

        detectionStatus = TextView(this@MainActivity).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(0), dp(8), dp(0), dp(8))
            setTextColor(color(Colors.muted))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }
        addView(detectionStatus, matchWidth())

        acousticStatus = TextView(this@MainActivity).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(0), dp(0), dp(0), dp(10))
            setTextColor(color(Colors.muted))
            setLineSpacing(dp(2).toFloat(), 1.15f)
            visibility = View.GONE
        }
        addView(acousticStatus, matchWidth())

        transcriptText = TextView(this@MainActivity).apply {
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setTextColor(color(Colors.ink))
            setPadding(0, dp(8), 0, 0)
        }
        addView(transcriptText, matchWidth())
    }

    private fun refreshPermissionState() = renderListeningState()

    private fun spinnerAdapter(items: List<String>) = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        items
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun selectionListener(onSelected: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 10f
        letterSpacing = 0.1f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(Colors.muted))
    }

    private fun updateSettings() {
        WhisperSettings.save(this, whisperSettings)
        val packagedModel = WhisperModel.fromFileName(getString(R.string.whisper_model_file))
        settingsHint.text = when (whisperSettings.detectionMode) {
            DetectionMode.WHISPER -> "Whisper transcribes speech and checks English output for three consecutive ‘help’ tokens. This build includes the ${packagedModel.label} model."
            DetectionMode.PSYCHOACOUSTIC -> "Psychoacoustic mode analyzes microphone audio directly. It does not use Whisper or create a transcript. Its experimental decisions are display only."
        }
    }

    private fun rounded(fill: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun color(rgb: Int): Int = Color.rgb(
        Color.red(rgb),
        Color.green(rgb),
        Color.blue(rgb)
    )

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun LinearLayout.LayoutParams.withTop(margin: Int) = apply { topMargin = margin }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private object Colors {
        val canvas = 0xFFF2F5F8.toInt()
        val white = 0xFFFFFFFF.toInt()
        val ink = 0xFF192638.toInt()
        val muted = 0xFF687485.toInt()
        val navy = 0xFF14263D.toInt()
        val blue = 0xFF285579.toInt()
        val heroBlue = 0xFF1F4567.toInt()
        val action = 0xFF167C78.toInt()
        val navyTint = 0xFF304B6B.toInt()
        val heroText = 0xFFD8E1EA.toInt()
        val coral = 0xFFB94D3D.toInt()
        val green = 0xFF34816C.toInt()
        val greenInk = 0xFF286653.toInt()
        val greenPale = 0xFFE5F1EB.toInt()
        val mint = 0xFFA7D6C4.toInt()
        val grayDot = 0xFFA7B0BA.toInt()
    }

    private companion object {
        const val MICROPHONE_REQUEST = 1001
        const val NOTIFICATION_REQUEST = 1002
    }
}
