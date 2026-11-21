package com.microwifi.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.AudioRouting
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MicService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var activeSession: Session? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val session = activeSession
                if (session == null) stopSelf() else requestStop(session)
            }
            ACTION_START -> if (activeSession == null) startTransmission(intent)
            ACTION_NOISE -> activeSession?.takeUnless { it.cancelled.get() }?.let { session ->
                val enabled = intent.getBooleanExtra(EXTRA_NOISE, false)
                session.noiseRequest.set(NoiseRequest(enabled))
                TransmissionState.publish(TransmissionState.current.copy(noiseRequested = enabled, noiseMessage = null))
            }
            else -> if (activeSession == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTransmission(intent: Intent) {
        val address = WireProtocol.parseIpv4(intent.getStringExtra(EXTRA_DESTINATION).orEmpty())
        if (address == null) {
            failToStart(R.string.invalid_ip)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failToStart(R.string.mic_permission)
            return
        }

        val destination = address.hostAddress.orEmpty()
        val wantsVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        var videoAllowed = wantsVideo && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        try {
            try {
                promote(destination, videoAllowed)
            } catch (e: SecurityException) {
                if (!videoAllowed) throw e
                Log.w(TAG, "Android no autorizó el servicio de cámara", e)
                videoAllowed = false
                promote(destination, false)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo iniciar el servicio en primer plano", e)
            failToStart(R.string.start_error)
            return
        }

        val network = wifiNetwork()
        if (network == null) {
            failToStart(R.string.no_wifi)
            return
        }

        val session = Session(network, address, destination)
        session.noiseRequest.set(NoiseRequest(intent.getBooleanExtra(EXTRA_NOISE, false)))
        activeSession = session
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            connectivity.registerNetworkCallback(request, session.networkCallback, main)
            session.observingNetwork = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo observar la red Wi-Fi", e)
            finish(session, R.string.network_error)
            return
        }
        // Cubre la pérdida de red entre la selección y el registro del callback.
        if (!isWifi(network)) {
            finish(session, R.string.wifi_lost)
            return
        }
        TransmissionState.publish(TransmissionState.current.copy(
            videoRequested = videoAllowed,
            noiseRequested = session.noiseRequest.get().enabled,
            videoMessage = when {
                videoAllowed -> R.string.video_preparing
                wantsVideo || intent.getBooleanExtra(EXTRA_VIDEO_DENIED, false) -> R.string.video_denied
                else -> null
            },
        ))
        Thread({ transmit(session) }, "MicroWifi-captura").start()
        if (videoAllowed) startVideo(session)
    }

    private fun startVideo(session: Session) {
        val links = connectivity.getLinkProperties(session.network)
        val local = links?.linkAddresses?.firstOrNull {
            val address = it.address as? Inet4Address
            address != null && sameWifiSubnet(address, session.address, it.prefixLength)
        }?.address as? Inet4Address
        if (local == null || local == session.address) {
            failVideo(session, R.string.video_subnet)
            return
        }
        session.videoAddress = local
        var firstFrame = false
        val server = MjpegServer(local, session.address,
            onListening = { main.post {
                val running = session.videoServer
                if (activeSession !== session || running == null) return@post
                val camera = VideoCamera(this, main, onFrame = { jpeg ->
                    running.publish(jpeg)
                    if (!firstFrame) {
                        firstFrame = true
                        TransmissionState.publish(TransmissionState.current.copy(
                            videoMessage = R.string.video_ready,
                            videoUrl = "http://${local.hostAddress}:${MjpegHttp.PORT}${MjpegHttp.PATH}",
                        ))
                    }
                }, onError = { e -> failVideo(session, R.string.video_error, e) })
                session.videoCamera = camera
                camera.start()
            } },
            onFailure = { e -> main.post { failVideo(session, R.string.video_error, e) } },
        )
        session.videoServer = server
        // Abrir solo tras iniciar el listener ligado a la dirección del Wi-Fi.
        server.start()
    }

    private fun failVideo(session: Session, message: Int, cause: Exception? = null) {
        if (activeSession !== session || session.cancelled.get() || !TransmissionState.current.videoRequested) return
        if (cause != null) Log.w(TAG, "Falló el vídeo MJPEG", cause)
        closeVideo(session)
        // Android puede conservar los tipos FGS acumulados; la cámara y el socket ya están cerrados.
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(session.destination, false))
        TransmissionState.publish(TransmissionState.current.copy(videoMessage = message, videoUrl = null, videoRequested = false))
    }

    private fun closeVideo(session: Session) {
        session.videoServer?.close()
        session.videoCamera?.close()
        session.videoServer = null
        session.videoCamera = null
        session.videoAddress = null
    }

    @Suppress("DEPRECATION") // Permite usar Wi-Fi local aunque el sistema prefiera datos móviles.
    private fun wifiNetwork(): Network? =
        connectivity.activeNetwork?.takeIf(::isWifi) ?: connectivity.allNetworks.firstOrNull(::isWifi)

    private fun isWifi(network: Network): Boolean = connectivity.getNetworkCapabilities(network)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } == true

    // El permiso se comprueba al iniciar. El finally interno suelta el wake lock incluso si falla release() del micrófono.
    @SuppressLint("MissingPermission", "Wakelock", "WakelockTimeout")
    private fun transmit(session: Session) {
        var error = R.string.audio_error
        var recorder: AudioRecord? = null
        var routeListener: AudioRouting.OnRoutingChangedListener? = null
        val isolation = VoiceIsolation()
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:captura")
        try {
            wakeLock.acquire()
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo mantener activa la captura", e)
            main.post { finish(session, if (session.cancelled.get()) session.stopMessage else R.string.start_error) }
            return
        }
        try {
            if (session.cancelled.get()) return
            error = R.string.network_error
            DatagramSocket().use { socket ->
                session.socket = socket
                if (session.cancelled.get()) return
                session.network.bindSocket(socket)
                val samples = ShortArray(WireProtocol.SAMPLES)
                val output = ShortArray(WireProtocol.SAMPLES)
                val packet = WireProtocol.Packet()
                val datagram = DatagramPacket(packet.bytes, packet.bytes.size, session.address, WireProtocol.PORT)
                error = R.string.audio_error
                val audio = openAudioCapture(this)
                recorder = audio
                routeListener = watchAudioRoute(audio, main)
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var sequence = 0
                var filled = 0
                var sent = false
                var appliedChoice: NoiseRequest? = null
                var appliedMode: VoiceIsolation.Mode? = null
                while (!session.cancelled.get()) {
                    val count = audio.read(samples, filled, samples.size - filled, AudioRecord.READ_BLOCKING)
                    if (session.cancelled.get()) break
                    check(count > 0) { "Lectura de AudioRecord: $count" }
                    filled += count
                    if (filled == samples.size) {
                        val choice = session.noiseRequest.get()
                        val mode = isolation.process(samples, output, choice.enabled)
                        if (appliedChoice !== choice || appliedMode != mode) {
                            appliedChoice = choice
                            appliedMode = mode
                            val message = when (mode) {
                                VoiceIsolation.Mode.FILTERED -> R.string.noise_enabled
                                VoiceIsolation.Mode.UNAVAILABLE -> R.string.noise_error
                                VoiceIsolation.Mode.BYPASS, VoiceIsolation.Mode.WARMING -> null
                            }
                            main.post {
                                if (activeSession === session && !session.cancelled.get() && session.noiseRequest.get() === choice) {
                                    TransmissionState.publish(TransmissionState.current.copy(noiseMessage = message))
                                }
                            }
                        }
                        packet.encode(sequence, output)
                        socket.send(datagram)
                        sequence++
                        filled = 0
                        if (!sent) {
                            sent = true
                            logAudioRoute(audio)
                            main.post {
                                if (activeSession === session && !session.cancelled.get()) {
                                    TransmissionState.publish(TransmissionState.current.copy(message = R.string.sending, destination = session.destination))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            error = R.string.network_error
            if (!session.cancelled.get()) Log.w(TAG, "Falló el envío UDP", e)
        } catch (e: RuntimeException) {
            if (!session.cancelled.get()) Log.w(TAG, "Falló la transmisión", e)
        } finally {
            session.socket = null
            try {
                isolation.close()
                routeListener?.let { listener -> runCatching { recorder?.removeOnRoutingChangedListener(listener) } }
                recorder?.release() // AudioRecord.release() detiene también una grabación activa.
            } finally {
                wakeLock.release()
                main.post { finish(session, if (session.cancelled.get()) session.stopMessage else error) }
            }
        }
    }

    private fun requestStop(session: Session, message: Int = R.string.idle) {
        if (!session.cancelled.compareAndSet(false, true)) return
        session.stopMessage = message
        closeVideo(session)
        TransmissionState.publish(TransmissionState.current.copy(message = R.string.stopping, destination = null, videoMessage = null, videoUrl = null))
        session.socket?.close()
    }

    private fun finish(session: Session, message: Int) {
        if (activeSession !== session) return
        closeVideo(session)
        activeSession = null
        unobserve(session)
        failToStart(message)
    }

    private fun failToStart(message: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TransmissionState.publish(TransmissionState.Status(message))
    }

    private fun unobserve(session: Session) {
        if (session.observingNetwork) {
            connectivity.unregisterNetworkCallback(session.networkCallback)
            session.observingNetwork = false
        }
    }

    override fun onDestroy() {
        activeSession?.let {
            requestStop(it)
            unobserve(it)
            activeSession = null
            TransmissionState.publish(TransmissionState.Status(R.string.idle))
        }
        super.onDestroy()
    }

    private fun promote(destination: String, video: Boolean) {
        val note = notification(destination, video)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            startForeground(NOTIFICATION_ID, note, type)
        } else {
            startForeground(NOTIFICATION_ID, note)
        }
    }

    private fun notification(destination: String, video: Boolean): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
        val stop = PendingIntent.getService(this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), pendingFlags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (video) R.string.video_notification else R.string.channel_name))
            .setContentText(getString(R.string.notification_target, destination, WireProtocol.PORT))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_short), stop).build())
            .build()
    }

    private class NoiseRequest(val enabled: Boolean)

    private inner class Session(val network: Network, val address: Inet4Address, val destination: String) {
        val cancelled = AtomicBoolean(false)
        val noiseRequest = AtomicReference(NoiseRequest(false))
        var videoAddress: Inet4Address? = null
        var videoServer: MjpegServer? = null
        var videoCamera: VideoCamera? = null
        var stopMessage = R.string.idle
        var observingNetwork = false
        @Volatile var socket: DatagramSocket? = null
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (network == lost && activeSession === this@Session) requestStop(this@Session, R.string.wifi_lost)
            }

            override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
                val local = videoAddress ?: return
                if (network == changed && activeSession === this@Session &&
                    properties.linkAddresses.none { it.address == local && sameWifiSubnet(local, address, it.prefixLength) }
                ) {
                    failVideo(this@Session, R.string.video_address_changed)
                }
            }
        }
    }

    companion object {
        internal const val ACTION_START = "com.microwifi.android.START"
        internal const val ACTION_STOP = "com.microwifi.android.STOP"
        internal const val ACTION_NOISE = "com.microwifi.android.NOISE"
        internal const val EXTRA_DESTINATION = "destination"
        internal const val EXTRA_VIDEO = "video"
        internal const val EXTRA_VIDEO_DENIED = "video_denied"
        internal const val EXTRA_NOISE = "noise"
        internal const val CHANNEL = "mic_transmission"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MicroWifi"
    }
}
