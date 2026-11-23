package com.monotonic.metronome.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Manages Android AudioFocus for active metronome sessions so ducking or
 * transient interruptions are handled cleanly.
 */
class AudioFocusController(
    context: Context,
    private val onVolumeScaleChanged: (Float) -> Unit
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> onVolumeScaleChanged(DEFAULT_VOLUME)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onVolumeScaleChanged(DUCKED_VOLUME)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> onVolumeScaleChanged(MUTED_VOLUME)
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

    private var hasFocus = false

    fun requestFocus(): Boolean {
        if (hasFocus) return true
        val res = audioManager.requestAudioFocus(focusRequest)
        hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (hasFocus) {
            onVolumeScaleChanged(DEFAULT_VOLUME)
        }
        return hasFocus
    }

    fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    companion object {
        const val DEFAULT_VOLUME = 0.85f
        const val DUCKED_VOLUME = 0.30f
        const val MUTED_VOLUME = 0.0f
    }
}
