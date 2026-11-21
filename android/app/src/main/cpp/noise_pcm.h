#ifndef MICROWIFI_NOISE_PCM_H
#define MICROWIFI_NOISE_PCM_H
#include <stdbool.h>
#include <stdint.h>
#include "rnnoise.h"

#define MW_FRAME 480
typedef struct MwVoice MwVoice;
MwVoice *mw_create(void);
void mw_destroy(MwVoice *state);
bool mw_process_pcm(MwVoice *state, const int16_t *in, int16_t *out);
#endif
