package com.monotonic.metronome.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

enum class AudioFocusAction {
    RESTORE_FULL_GAIN,
    DUCK_GAIN,
    MUTE_TRANSIENT,
    STOP_PLAYBACK_PERMANENT,
    IGNORE
}

object AudioFocusPolicy {
    fun evaluate(focusChange: Int): AudioFocusAction {
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> AudioFocusAction.RESTORE_FULL_GAIN
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusAction.DUCK_GAIN
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusAction.MUTE_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusAction.STOP_PLAYBACK_PERMANENT
            else -> AudioFocusAction.IGNORE
        }
    }
}

/**
 * Manages Android AudioFocus for active metronome sessions using a normalized
 * gain multiplier (`1.0f` full, `0.35f` ducked, `0.0f` muted) so user master
 * volume preferences remain intact.
 *
 * Transient losses duck or mute cleanly without tearing down playback or any
 * active Sync Room WebSocket; only permanent [AudioManager.AUDIOFOCUS_LOSS]
 * invokes [onPermanentFocusLoss] to stop local playback (still keeping the
 * Sync Room WebSocket alive).
 */
class AudioFocusController(
    context: Context,
    private val onFocusGainChanged: (Float) -> Unit,
    private val onPermanentFocusLoss: () -> Unit = {}
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusLock = Any()
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (AudioFocusPolicy.evaluate(focusChange)) {
            AudioFocusAction.RESTORE_FULL_GAIN -> onFocusGainChanged(GAIN_FULL)
            AudioFocusAction.DUCK_GAIN -> onFocusGainChanged(GAIN_DUCKED)
            AudioFocusAction.MUTE_TRANSIENT -> onFocusGainChanged(GAIN_MUTED)
            AudioFocusAction.STOP_PLAYBACK_PERMANENT -> {
                abandonFocus()
                onPermanentFocusLoss()
            }
            AudioFocusAction.IGNORE -> Unit
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

    fun requestFocus(): Boolean {
        val granted = synchronized(focusLock) {
            if (hasFocus) return true
            val res = audioManager.requestAudioFocus(focusRequest)
            hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            hasFocus
        }
        if (granted) {
            onFocusGainChanged(GAIN_FULL)
        }
        return granted
    }

    fun abandonFocus() {
        synchronized(focusLock) {
            if (!hasFocus) return
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    companion object {
        const val GAIN_FULL = 1.0f
        const val GAIN_DUCKED = 0.35f
        const val GAIN_MUTED = 0.0f
    }
}
