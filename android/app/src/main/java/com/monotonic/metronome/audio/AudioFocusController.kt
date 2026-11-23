package com.monotonic.metronome.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Manages Android AudioFocus for active metronome sessions using a normalized
 * gain multiplier (`1.0f` full, `0.35f` ducked, `0.0f` muted) so user master
 * volume preferences remain intact.
 */
class AudioFocusController(
    context: Context,
    private val onFocusGainChanged: (Float) -> Unit
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusGainChanged(GAIN_FULL)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusGainChanged(GAIN_DUCKED)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> onFocusGainChanged(GAIN_MUTED)
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
            onFocusGainChanged(GAIN_FULL)
        }
        return hasFocus
    }

    fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    companion object {
        const val GAIN_FULL = 1.0f
        const val GAIN_DUCKED = 0.35f
        const val GAIN_MUTED = 0.0f
    }
}
