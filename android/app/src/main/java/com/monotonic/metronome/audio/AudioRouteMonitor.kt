package com.monotonic.metronome.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.monotonic.metronome.model.AudioRouteState
import com.monotonic.metronome.model.AudioRouteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors active audio output routes so the app can warn about Bluetooth/speaker
 * latency and restart the Oboe stream cleanly on headphone plug/unplug events.
 */
class AudioRouteMonitor(
    context: Context,
    private val onRouteChanged: () -> Unit
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(evaluateCurrentRoute())
    val state: StateFlow<AudioRouteState> = _state.asStateFlow()

    private var isRegistered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink }) {
                refreshAndNotify()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) {
                refreshAndNotify()
            }
        }
    }

    fun start() {
        if (isRegistered) return
        isRegistered = true
        _state.value = evaluateCurrentRoute()
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
    }

    fun stop() {
        if (!isRegistered) return
        isRegistered = false
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun refreshAndNotify() {
        _state.value = evaluateCurrentRoute()
        onRouteChanged()
    }

    private fun evaluateCurrentRoute(): AudioRouteState {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val wired = outputs.firstOrNull { isWiredDevice(it.type) }
        if (wired != null) {
            return AudioRouteState(
                status = AudioRouteStatus.WIRED_READY,
                deviceLabel = wiredLabel(wired)
            )
        }

        val bluetooth = outputs.firstOrNull { isBluetoothDevice(it.type) }
        if (bluetooth != null) {
            val label = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: "Bluetooth Audio"
            return AudioRouteState(
                status = AudioRouteStatus.BLUETOOTH_WARNING,
                deviceLabel = label
            )
        }

        return AudioRouteState(
            status = AudioRouteStatus.SPEAKER_WARNING,
            deviceLabel = "Phone Speaker"
        )
    }

    private fun isWiredDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    private fun isBluetoothDevice(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            ) {
                return true
            }
        }
        return false
    }

    private fun wiredLabel(info: AudioDeviceInfo): String {
        val product = info.productName?.toString()?.takeIf { it.isNotBlank() }
        return when (info.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> product ?: "USB-C Wired Audio"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> product ?: "3.5mm Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> product ?: "3.5mm Wired Headphones"
            else -> product ?: "Wired Audio"
        }
    }
}
