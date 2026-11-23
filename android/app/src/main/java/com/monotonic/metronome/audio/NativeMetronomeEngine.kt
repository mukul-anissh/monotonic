package com.monotonic.metronome.audio

/**
 * JNI bridge to the native C++ Google Oboe low-latency metronome engine.
 */
class NativeMetronomeEngine {

    private var nativeHandle: Long = nativeCreate()

    fun startStream(): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeStartStream(handle)
    }

    fun stopStream() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeStopStream(handle)
        }
    }

    fun restartStream(): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeRestartStream(handle)
    }

    fun updateTransport(
        isPlaying: Boolean,
        localStartTimeNs: Long,
        bpm: Double,
        beatsPerBar: Int,
        subdivision: Int,
        manualOffsetNs: Long,
        volume: Float,
        stateVersion: Long
    ) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeUpdateTransport(
            handle = handle,
            isPlaying = isPlaying,
            localStartTimeNs = localStartTimeNs,
            bpm = bpm,
            beatsPerBar = beatsPerBar,
            subdivision = subdivision,
            manualOffsetNs = manualOffsetNs,
            volume = volume,
            stateVersion = stateVersion
        )
    }

    fun setAccentBitmask(bitmask: Int) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeSetAccentBitmask(handle, bitmask)
        }
    }

    fun setSoundProfile(profileIndex: Int) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeSetSoundProfile(handle, profileIndex)
        }
    }

    fun getCurrentBeatIndex(): Int {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetCurrentBeatIndex(handle) else -1
    }

    fun getBeatSequence(): Long {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetBeatSequence(handle) else 0L
    }

    fun getMeasuredHardwareLatencyMs(): Double {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetMeasuredHardwareLatencyMs(handle) else 0.0
    }

    fun getSampleRate(): Int {
        val handle = nativeHandle
        return if (handle != 0L) nativeGetSampleRate(handle) else 48000
    }

    fun isExclusiveSharingMode(): Boolean {
        val handle = nativeHandle
        return if (handle != 0L) nativeIsExclusiveSharingMode(handle) else false
    }

    fun getAudioApiName(): String {
        val handle = nativeHandle
        if (handle == 0L) return "Unspecified"
        return when (nativeGetAudioApi(handle)) {
            1 -> "OpenSL ES"
            2 -> "AAudio"
            else -> "Auto"
        }
    }

    fun destroy() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeDestroy(handle)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStartStream(handle: Long): Boolean
    private external fun nativeStopStream(handle: Long)
    private external fun nativeRestartStream(handle: Long): Boolean
    private external fun nativeUpdateTransport(
        handle: Long,
        isPlaying: Boolean,
        localStartTimeNs: Long,
        bpm: Double,
        beatsPerBar: Int,
        subdivision: Int,
        manualOffsetNs: Long,
        volume: Float,
        stateVersion: Long
    )
    private external fun nativeSetAccentBitmask(handle: Long, bitmask: Int)
    private external fun nativeSetSoundProfile(handle: Long, profileIndex: Int)
    private external fun nativeGetCurrentBeatIndex(handle: Long): Int
    private external fun nativeGetBeatSequence(handle: Long): Long
    private external fun nativeGetMeasuredHardwareLatencyMs(handle: Long): Double
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeIsExclusiveSharingMode(handle: Long): Boolean
    private external fun nativeGetAudioApi(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("monotonic_audio")
        }
    }
}
