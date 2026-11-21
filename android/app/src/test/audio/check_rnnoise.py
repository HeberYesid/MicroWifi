"""Comprueba el DSP nativo con voz limpia + ruido reproducible, sin tocar el APK.

Uso: python check_rnnoise.py <biblioteca-host> <voz PCM16 mono 48 kHz.wav>
La voz debe incluir varios segundos de habla. La prueba evalúa ventanas con voz,
no el silencio entre frases. No sustituye una grabación acústica en un teléfono.
"""

import ctypes as c
import math
import random
import statistics
import sys
import wave

FRAME = 480
RATE = 48000


def snr_projection(reference, audio):
    energy = sum(x * x for x in reference)
    gain = sum(x * y for x, y in zip(reference, audio)) / energy
    residual = sum((y - gain * x) ** 2 for x, y in zip(reference, audio))
    return 10 * math.log10(max(1, gain * gain * energy) / max(1, residual)), gain


def tone_power(samples, starts, width):
    total = 0
    for hz in (120, 240):
        basis = [
            (math.cos(math.tau * hz * j / RATE), math.sin(math.tau * hz * j / RATE))
            for j in range(width)
        ]
        for i in starts:
            segment = samples[i : i + width]
            re = sum(x * cosine for x, (cosine, _) in zip(segment, basis))
            im = sum(x * sine for x, (_, sine) in zip(segment, basis))
            total += re * re + im * im
    return total


def process(lib, samples):
    state = lib.mw_create()
    if not state:
        raise RuntimeError("No se pudo iniciar RNNoise")
    output = []
    frame = c.c_int16 * FRAME
    try:
        for offset in range(0, len(samples), FRAME):
            dest = frame()
            if not lib.mw_process_pcm(
                state, frame(*samples[offset : offset + FRAME]), dest
            ):
                raise RuntimeError("RNNoise devolvió salida no válida")
            output.extend(dest)
    finally:
        lib.mw_destroy(state)
    return output[FRAME:]


def main(lib_path, voice_wav):
    with wave.open(voice_wav, "rb") as f:
        assert (f.getnchannels(), f.getsampwidth(), f.getframerate()) == (1, 2, RATE)
        size = f.getnframes() // FRAME * FRAME
        clean = list((c.c_int16 * size).from_buffer_copy(f.readframes(size)))
    peak = max(map(abs, clean))
    clean = [round(9500 * x / peak) for x in clean]
    lib = c.CDLL(lib_path)
    lib.mw_create.argtypes = []
    lib.mw_create.restype = c.c_void_p
    lib.mw_destroy.argtypes = [c.c_void_p]
    lib.mw_destroy.restype = None
    lib.mw_process_pcm.argtypes = [
        c.c_void_p,
        c.POINTER(c.c_int16),
        c.POINTER(c.c_int16),
    ]
    lib.mw_process_pcm.restype = c.c_bool

    rng = random.Random(1450)
    low = 0.0
    fan = []
    keys = [0] * size
    for i in range(size):
        low = 0.94 * low + rng.uniform(-1600, 1600) * 0.1
        t = i / RATE
        fan.append(
            round(
                low
                + 590 * math.sin(math.tau * 120 * t)
                + 280 * math.sin(math.tau * 240 * t)
            )
        )
    # Golpes solo en presencia de voz; cada golpe dura ~18 ms.
    taps = []
    for i in range(RATE // 2, size - 2 * FRAME, 12 * FRAME):
        if sum(x * x for x in clean[i : i + FRAME]) / FRAME < 450**2:
            continue
        taps.append(i)
        for j in range(2 * FRAME):
            keys[i + j] = round(6500 * math.exp(-j / 210) * rng.uniform(-1, 1))
    assert len(taps) >= 8, (
        "Se requieren al menos 8 ventanas de voz con teclado superpuesto"
    )

    # La atenuación de los tonos del motor no equivale a mejorar la relación global voz/distorsión.
    filtered_clean = process(lib, clean + [0] * FRAME)
    fan_only = process(lib, fan + [0] * FRAME)
    assert sum(x * x for x in fan_only[RATE:]) < sum(x * x for x in fan[RATE:]) * 0.25
    for label, noise, starts, width in [
        ("ventilador", fan, range(RATE // 2, size - 3 * FRAME, 2 * FRAME), 2 * FRAME),
        ("teclado + ventilador", [a + b for a, b in zip(fan, keys)], taps, 2 * FRAME),
    ]:
        mixed = [a + b for a, b in zip(clean, noise)]
        output = process(lib, mixed + [0] * FRAME)
        assert max(map(abs, output)) <= 31001
        improvements, gains, active = [], [], []
        for i in starts:
            ref = clean[i : i + width]
            if sum(x * x for x in ref) / width < 450**2:
                continue
            before, _ = snr_projection(ref, mixed[i : i + width])
            after, gain = snr_projection(ref, output[i : i + width])
            improvements.append(after - before)
            gains.append(gain)
            active.append(i)
        improvement = statistics.median(improvements)
        gain = statistics.median(gains)
        print(
            f"{label}: {len(gains)} ventanas con voz, mejora mediana {improvement:+.2f} dB, ganancia voz {gain:.2f}"
        )
        assert gain >= 1.1, "La voz de prueba debe recuperar nivel tras el filtro"
        if label == "ventilador":
            residual = [a - b for a, b in zip(output, filtered_clean)]
            db = 10 * math.log10(
                tone_power(fan, active, width)
                / max(1, tone_power(residual, active, width))
            )
            print(f"  atenuación tonal absoluta durante la voz: {db:+.2f} dB")
            # La compensación aumenta tanto la voz como el residuo durante el habla.
            # Compara los tonos al mismo nivel de voz; las pausas se evalúan sin normalizar.
            assert db + 20 * math.log10(gain) >= 1.0
        else:
            assert improvement >= 2.0


if __name__ == "__main__":
    main(*sys.argv[1:])
