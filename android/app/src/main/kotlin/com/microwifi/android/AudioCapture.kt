package com.microwifi.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.os.Handler
import android.util.Log

private const val TAG = "MicroWifiAudio"

@SuppressLint("MissingPermission") // MicService comprueba RECORD_AUDIO antes de iniciar la captura.
internal fun openAudioCapture(context: Context): AudioRecord {
    val manager = context.getSystemService(AudioManager::class.java)
    val rawSupported = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    val sources = if (rawSupported) listOf(AudioSource.UNPROCESSED, AudioSource.VOICE_RECOGNITION, AudioSource.MIC)
        else listOf(AudioSource.VOICE_RECOGNITION, AudioSource.MIC)
    val channel = AudioFormat.CHANNEL_IN_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val rate = WireProtocol.SAMPLE_RATE
    val minBytes = AudioRecord.getMinBufferSize(rate, channel, encoding)
    check(minBytes > 0) { "Formato PCM no soportado: $minBytes" }
    var failure: RuntimeException? = null
    for (source in sources) {
        var record: AudioRecord? = null
        try {
            val candidate = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(channel).setEncoding(encoding).build())
                .setBufferSizeInBytes(maxOf(minBytes, WireProtocol.PAYLOAD_BYTES * 4))
                .build()
            record = candidate
            check(candidate.state == AudioRecord.STATE_INITIALIZED && candidate.sampleRate == rate &&
                candidate.channelCount == 1 && candidate.audioFormat == encoding)
            candidate.startRecording()
            check(candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            Log.i(TAG, "Fuente solicitada=${sourceName(source)}; PCM cliente=$rate Hz mono 16 bits; UNPROCESSED anunciado=$rawSupported")
            return candidate
        } catch (e: SecurityException) {
            runCatching { record?.release() }
            throw e
        } catch (e: RuntimeException) {
            runCatching { record?.release() }
            failure = e
            Log.w(TAG, "No se pudo usar ${sourceName(source)}", e)
        }
    }
    throw IllegalStateException("No se pudo abrir una fuente de captura a 48 kHz", failure)
}

// Se consulta tras la primera lectura; Android no siempre tiene la ruta al llamar a startRecording().
internal fun logAudioRoute(record: AudioRecord) {
    runCatching {
        val routeType = record.routedDevice?.type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = record.activeRecordingConfiguration
            Log.i(TAG, "Ruta tipo=$routeType; PCM de dispositivo=${config?.format}; " +
                "efectos visibles=${config?.effects?.joinToString { it.name }}; silenciado=${config?.isClientSilenced}")
        } else {
            Log.i(TAG, "Ruta tipo=$routeType; Android anterior a 10 no expone aquí los efectos activos")
        }
    }.onFailure { Log.w(TAG, "No se pudo consultar la ruta de captura", it) }
}

internal fun watchAudioRoute(record: AudioRecord, handler: Handler): AudioRouting.OnRoutingChangedListener? = runCatching {
    val listener = AudioRouting.OnRoutingChangedListener { logAudioRoute(record) }
    record.addOnRoutingChangedListener(listener, handler)
    listener
}.getOrNull()

private fun sourceName(source: Int): String = when (source) {
    AudioSource.UNPROCESSED -> "UNPROCESSED"
    AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
    else -> "MIC"
}
