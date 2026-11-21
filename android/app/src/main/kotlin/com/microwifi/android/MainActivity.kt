package com.microwifi.android

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private lateinit var destination: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var notificationHelp: TextView
    private lateinit var videoOption: Switch
    private lateinit var videoStatus: TextView
    private lateinit var videoUrl: TextView
    private lateinit var noiseOption: Switch
    private lateinit var noiseStatus: TextView
    private val cameraAvailable by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    private val noiseAvailable by lazy { NativeVoiceFilter.available }
    private var rendering = false
    private var pendingVideo = false
    private var pendingNoise = false
    private var cameraDenied = false
    private var pendingAddress: String? = null
    private var readyToStart = false
    private var resumed = false
    private val observer: (TransmissionState.Status) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        destination = findViewById(R.id.destination)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        notificationHelp = findViewById(R.id.notification_help)
        videoOption = findViewById(R.id.video_option)
        videoStatus = findViewById(R.id.video_status)
        videoUrl = findViewById(R.id.video_url)
        noiseOption = findViewById(R.id.noise_option)
        noiseStatus = findViewById(R.id.noise_status)
        pendingAddress = savedInstanceState?.getString(PENDING_ADDRESS)
        pendingVideo = savedInstanceState?.getBoolean("pending_video") == true
        pendingNoise = savedInstanceState?.getBoolean("pending_noise") == true
        cameraDenied = savedInstanceState?.getBoolean("camera_denied") == true
        readyToStart = savedInstanceState?.getBoolean(READY_TO_START) == true
        if (savedInstanceState == null) destination.setText(preferences.getString(SAVED_ADDRESS, ""))

        // Android 15 coloca el contenido bajo las barras del sistema al usar targetSdk 35.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val screen = findViewById<View>(R.id.screen)
            screen.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            screen.requestApplyInsets()
        }

        toggle.setOnClickListener {
            if (TransmissionState.current.active) {
                startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
            } else {
                askToTransmit()
            }
        }
        noiseOption.setOnCheckedChangeListener { _, enabled ->
            if (!rendering && TransmissionState.current.active) {
                startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_NOISE)
                    .putExtra(MicService.EXTRA_NOISE, enabled))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TransmissionState.observe(observer)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (readyToStart) startTransmission()
        render(TransmissionState.current)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        TransmissionState.remove(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_ADDRESS, pendingAddress)
        outState.putBoolean("pending_video", pendingVideo)
        outState.putBoolean("pending_noise", pendingNoise)
        outState.putBoolean("camera_denied", cameraDenied)
        outState.putBoolean(READY_TO_START, readyToStart)
        super.onSaveInstanceState(outState)
    }

    private fun askToTransmit() {
        val address = WireProtocol.parseIpv4(destination.text.toString())?.hostAddress
        if (address == null) {
            destination.requestFocus()
            destination.error = getString(R.string.invalid_ip)
            return
        }
        destination.error = null
        preferences.edit().putString(SAVED_ADDRESS, address).apply()
        pendingAddress = address
        pendingVideo = cameraAvailable && videoOption.isChecked
        pendingNoise = noiseAvailable && noiseOption.isChecked
        cameraDenied = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askForCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    private fun askForCamera() {
        if (pendingVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            askForNotifications()
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean(ASKED_NOTIFICATIONS, false)
        ) {
            preferences.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            startTransmission()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MIC -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                askForCamera()
            } else {
                pendingAddress = null
                TransmissionState.publish(TransmissionState.Status(R.string.mic_permission))
            }
            REQUEST_CAMERA -> {
                cameraDenied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
                if (cameraDenied) {
                    pendingVideo = false
                    videoOption.isChecked = false
                }
                askForNotifications()
            }
            // Android permite el servicio aunque el usuario no habilite la notificación.
            REQUEST_NOTIFICATIONS -> startTransmission()
        }
    }

    private fun startTransmission() {
        // El resultado del permiso puede llegar antes de que la actividad vuelva a estar visible.
        if (!resumed) {
            readyToStart = pendingAddress != null
            return
        }
        readyToStart = false
        val address = pendingAddress ?: return
        pendingAddress = null
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(destination.windowToken, 0)
        destination.clearFocus()
        TransmissionState.publish(TransmissionState.Status(
            R.string.starting, true, videoRequested = pendingVideo, noiseRequested = pendingNoise,
            videoMessage = if (cameraDenied) R.string.video_denied else null,
        ))
        try {
            val service = Intent(this, MicService::class.java).setAction(MicService.ACTION_START)
                .putExtra(MicService.EXTRA_DESTINATION, address)
                .putExtra(MicService.EXTRA_VIDEO, pendingVideo)
                .putExtra(MicService.EXTRA_VIDEO_DENIED, cameraDenied)
                .putExtra(MicService.EXTRA_NOISE, pendingNoise)
            startForegroundService(service)
        } catch (_: IllegalStateException) {
            TransmissionState.publish(TransmissionState.Status(R.string.start_error))
        } catch (_: SecurityException) {
            TransmissionState.publish(TransmissionState.Status(R.string.mic_permission))
        }
    }

    private fun render(state: TransmissionState.Status) {
        destination.isEnabled = !state.active
        toggle.setText(if (state.active) R.string.stop else R.string.start)
        toggle.isEnabled = state.message != R.string.stopping
        rendering = true
        if (state.active) {
            videoOption.isChecked = state.videoRequested
            noiseOption.isChecked = state.noiseRequested
        }
        rendering = false
        videoOption.isEnabled = cameraAvailable && !state.active
        noiseOption.isEnabled = noiseAvailable && state.message !in listOf(R.string.starting, R.string.stopping)
        videoStatus.setText(state.videoMessage ?: if (cameraAvailable) R.string.video_help else R.string.video_missing)
        videoUrl.text = state.videoUrl
        videoUrl.visibility = if (state.videoUrl == null) View.GONE else View.VISIBLE
        noiseStatus.setText(state.noiseMessage ?: if (noiseAvailable) R.string.noise_help else R.string.noise_unavailable)
        status.text = if (state.destination == null) getString(state.message)
            else getString(state.message, state.destination, WireProtocol.PORT)
        val notifications = getSystemService(NotificationManager::class.java)
        val channelDisabled = notifications.getNotificationChannel(MicService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        notificationHelp.visibility = if (state.active && (!notifications.areNotificationsEnabled() || channelDisabled)) {
            View.VISIBLE
        } else View.GONE
    }

    companion object {
        private const val REQUEST_MIC = 1
        private const val REQUEST_NOTIFICATIONS = 2
        private const val REQUEST_CAMERA = 3
        private const val SAVED_ADDRESS = "ipv4"
        private const val ASKED_NOTIFICATIONS = "asked_notifications"
        private const val PENDING_ADDRESS = "pending_address"
        private const val READY_TO_START = "ready_to_start"
    }
}
