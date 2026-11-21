package com.microwifi.android

internal object TransmissionState {
    data class Status(
        val message: Int,
        val active: Boolean = false,
        val destination: String? = null,
        val videoMessage: Int? = null,
        val videoUrl: String? = null,
        val noiseMessage: Int? = null,
        val videoRequested: Boolean = false,
        val noiseRequested: Boolean = false,
    )

    // Toda lectura, modificación y notificación se realiza en el hilo principal.
    private val listeners = mutableSetOf<(Status) -> Unit>()
    var current = Status(R.string.idle)
        private set

    fun publish(status: Status) {
        current = status
        listeners.toList().forEach { it(status) }
    }

    fun observe(listener: (Status) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun remove(listener: (Status) -> Unit) {
        listeners -= listener
    }
}
