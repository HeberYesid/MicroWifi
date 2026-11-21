#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include "noise_pcm.h"

static pthread_mutex_t initialize_lock = PTHREAD_MUTEX_INITIALIZER;

/* Kotlin es el único dueño del estado, desde el hilo de captura. */
JNIEXPORT jlong JNICALL Java_com_microwifi_android_NativeVoiceFilter_create(JNIEnv *env, jobject self) {
    if (rnnoise_get_frame_size() != MW_FRAME) return 0;
    pthread_mutex_lock(&initialize_lock);
    MwVoice *state = mw_create();
    pthread_mutex_unlock(&initialize_lock);
    return (jlong)(intptr_t)state;
}

JNIEXPORT jboolean JNICALL Java_com_microwifi_android_NativeVoiceFilter_process(
    JNIEnv *env, jobject self, jlong handle, jshortArray input, jshortArray output) {
    if (!handle || !input || !output || (*env)->GetArrayLength(env, input) != MW_FRAME ||
        (*env)->GetArrayLength(env, output) != MW_FRAME) return JNI_FALSE;
    jshort in[MW_FRAME];
    jshort out[MW_FRAME];
    (*env)->GetShortArrayRegion(env, input, 0, MW_FRAME, in);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    if (!mw_process_pcm((MwVoice *)(intptr_t)handle, in, out)) return JNI_FALSE;
    (*env)->SetShortArrayRegion(env, output, 0, MW_FRAME, out);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_microwifi_android_NativeVoiceFilter_destroy(
    JNIEnv *env, jobject self, jlong handle) {
    if (handle) mw_destroy((MwVoice *)(intptr_t)handle);
}
