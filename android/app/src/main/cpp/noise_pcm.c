#include "noise_pcm.h"
#include <math.h>
#include <stdlib.h>

struct MwVoice { DenoiseState *filter; float gain; };
MwVoice *mw_create(void) {
    MwVoice *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->filter = rnnoise_create(NULL);
    if (!s->filter) { free(s); return NULL; }
    s->gain = 1.f;
    return s;
}
void mw_destroy(MwVoice *s) {
    if (s) { rnnoise_destroy(s->filter); free(s); }
}

bool mw_process_pcm(MwVoice *state, const int16_t *in, int16_t *out) {
    float input[MW_FRAME];
    float output[MW_FRAME];
    for (int i = 0; i < MW_FRAME; ++i) input[i] = in[i];
    const float speech = rnnoise_process_frame(state->filter, output, input);
    float peak = 0.f;
    for (int i = 0; i < MW_FRAME; ++i) {
        if (!isfinite(output[i])) return false;
        peak = fmaxf(peak, fabsf(output[i]));
    }
    // Hasta +6 dB solo si RNNoise detecta voz. Un bloque ya disponible da margen
    // para limitar los picos sin añadir latencia ni recortar las ondas.
    const float target = 1.f + fminf(1.f, fmaxf(0.f, (speech - .35f) / .45f));
    const float ceiling = 31000.f / fmaxf(1.f, peak);
    const float start = fminf(state->gain, ceiling);
    const float end = fminf(start + .35f * (target - start), ceiling);
    state->gain = end;
    for (int i = 0; i < MW_FRAME; ++i) {
        const float x = output[i] * (start + (end - start) * ((i + 1.f) / MW_FRAME));
        if (!isfinite(x)) return false;
        out[i] = x >= 32767.f ? 32767 : x <= -32768.f ? -32768 : (int16_t)lrintf(x);
    }
    return true;
}
