#include <jni.h>
#include <cstdint>

#include "MetronomeEngine.h"

namespace {
inline monotonic::MetronomeEngine* fromHandle(jlong handle) {
    return reinterpret_cast<monotonic::MetronomeEngine*>(handle);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeCreate(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    auto* engine = new monotonic::MetronomeEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    delete engine;
}

JNIEXPORT jboolean JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeStartStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    return engine->startStream() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeStopStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (engine) {
        engine->stopStream();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeRestartStream(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    return engine->restartStream() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeUpdateTransport(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle,
    jboolean isPlaying,
    jlong localStartTimeNs,
    jdouble bpm,
    jint beatsPerBar,
    jint subdivision,
    jlong manualOffsetNs,
    jfloat volume,
    jlong stateVersion
) {
    auto* engine = fromHandle(handle);
    if (!engine) return;

    monotonic::TransportParams params;
    params.isPlaying = (isPlaying == JNI_TRUE);
    params.localStartTimeNs = static_cast<int64_t>(localStartTimeNs);
    params.bpm = static_cast<double>(bpm);
    params.beatsPerBar = static_cast<int32_t>(beatsPerBar);
    params.subdivision = static_cast<int32_t>(subdivision);
    params.manualOffsetNs = static_cast<int64_t>(manualOffsetNs);
    params.volume = static_cast<float>(volume);
    params.stateVersion = static_cast<int64_t>(stateVersion);

    engine->updateTransport(params);
}

JNIEXPORT jint JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeGetCurrentBeatIndex(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    return engine ? static_cast<jint>(engine->getCurrentBeatIndex()) : -1;
}

JNIEXPORT jlong JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeGetBeatSequence(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    return engine ? static_cast<jlong>(engine->getBeatSequence()) : 0L;
}

JNIEXPORT jdouble JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeGetMeasuredHardwareLatencyMs(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    return engine ? static_cast<jdouble>(engine->getMeasuredHardwareLatencyMs()) : 0.0;
}

JNIEXPORT jint JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeGetSampleRate(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    return engine ? static_cast<jint>(engine->getSampleRate()) : 48000;
}

JNIEXPORT jboolean JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeIsExclusiveSharingMode(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return JNI_FALSE;
    return engine->isExclusiveSharingMode() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_monotonic_metronome_audio_NativeMetronomeEngine_nativeGetAudioApi(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    return engine ? static_cast<jint>(engine->getAudioApi()) : 0;
}

}  // extern "C"
