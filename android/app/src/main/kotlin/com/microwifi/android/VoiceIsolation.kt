package com.microwifi.android

import java.io.Closeable

internal object NativeVoiceFilter {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("microwifi_noise")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    external fun create(): Long
    external fun process(handle: Long, input: ShortArray, output: ShortArray): Boolean
    external fun destroy(handle: Long)
}

internal interface VoiceFilter : Closeable {
    fun process(input: ShortArray, output: ShortArray): Boolean
}

private fun openNativeFilter(): VoiceFilter? {
    if (!NativeVoiceFilter.available) return null
    val handle = NativeVoiceFilter.create()
    if (handle == 0L) return null
    return object : VoiceFilter {
        override fun process(input: ShortArray, output: ShortArray) = NativeVoiceFilter.process(handle, input, output)
        override fun close() = NativeVoiceFilter.destroy(handle)
    }
}

// Un bloque de retardo también en bypass, para que el interruptor no salte ni repita audio.
internal class VoiceIsolation(private val open: () -> VoiceFilter? = ::openNativeFilter) : Closeable {
    enum class Mode { BYPASS, WARMING, FILTERED, UNAVAILABLE }

    private val previous = ShortArray(WireProtocol.SAMPLES)
    private val filtered = ShortArray(WireProtocol.SAMPLES)
    private var engine: VoiceFilter? = null
    private var primed = false
    private var request = false
    private var mode = Mode.BYPASS

    fun process(input: ShortArray, output: ShortArray, enabled: Boolean): Mode {
        previous.copyInto(output)
        if (request != enabled) {
            request = enabled
            if (enabled) {
                engine = runCatching(open).getOrNull()
                mode = if (engine == null) Mode.UNAVAILABLE else Mode.WARMING
            } else {
                close()
                mode = Mode.BYPASS
            }
        }
        engine?.let { filter ->
            if (!runCatching { filter.process(input, filtered) }.getOrDefault(false)) {
                close()
                mode = Mode.UNAVAILABLE
            } else if (primed) {
                filtered.copyInto(output)
                mode = Mode.FILTERED
            } else {
                primed = true
            }
        }
        input.copyInto(previous)
        return mode
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
        primed = false
    }
}
