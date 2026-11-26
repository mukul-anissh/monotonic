package com.monotonic.metronome.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monotonic.metronome.audio.AccentPattern
import com.monotonic.metronome.model.ClickSoundOption
import com.monotonic.metronome.sync.SoloScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "monotonic_prefs")

data class UserPreferencesSnapshot(
    val serverUrl: String = PreferencesRepository.DEFAULT_SERVER_URL,
    val hasSavedServerUrl: Boolean = false,
    val manualOffsetMs: Int = 0,
    val masterVolume: Float = PreferencesRepository.DEFAULT_MASTER_VOLUME,
    val clickSound: ClickSoundOption = ClickSoundOption.CLASSIC,
    val visualBeatFlash: Boolean = true,
    val accentPatternRaw: String? = null,
    val soloBpm: Double = 120.0,
    val soloBeatsPerBar: Int = 4,
    val soloSubdivision: Int = 1
)

class PreferencesRepository(private val context: Context) {

    companion object {
        const val DEFAULT_SERVER_URL = "ws://192.168.1.100:8000/ws"
        const val MIN_MANUAL_OFFSET_MS = -50
        const val MAX_MANUAL_OFFSET_MS = 50
        const val DEFAULT_MASTER_VOLUME = 0.85f

        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_MANUAL_OFFSET_MS = intPreferencesKey("manual_offset_ms")
        private val KEY_MASTER_VOLUME = floatPreferencesKey("master_volume")
        private val KEY_CLICK_SOUND = intPreferencesKey("click_sound")
        private val KEY_VISUAL_BEAT_FLASH = booleanPreferencesKey("visual_beat_flash")
        private val KEY_ACCENT_PATTERN = stringPreferencesKey("accent_pattern")
        private val KEY_SOLO_BPM = floatPreferencesKey("solo_bpm")
        private val KEY_SOLO_BEATS_PER_BAR = intPreferencesKey("solo_beats_per_bar")
        private val KEY_SOLO_SUBDIVISION = intPreferencesKey("solo_subdivision")
    }

    val preferencesFlow: Flow<UserPreferencesSnapshot> = context.dataStore.data.map { prefs ->
        val rawSub = prefs[KEY_SOLO_SUBDIVISION] ?: 1
        UserPreferencesSnapshot(
            serverUrl = prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL,
            hasSavedServerUrl = prefs.contains(KEY_SERVER_URL),
            manualOffsetMs = (prefs[KEY_MANUAL_OFFSET_MS] ?: 0)
                .coerceIn(MIN_MANUAL_OFFSET_MS, MAX_MANUAL_OFFSET_MS),
            masterVolume = (prefs[KEY_MASTER_VOLUME] ?: DEFAULT_MASTER_VOLUME).coerceIn(0f, 1f),
            clickSound = ClickSoundOption.fromId(prefs[KEY_CLICK_SOUND] ?: ClickSoundOption.CLASSIC.id),
            visualBeatFlash = prefs[KEY_VISUAL_BEAT_FLASH] ?: true,
            accentPatternRaw = prefs[KEY_ACCENT_PATTERN],
            soloBpm = (prefs[KEY_SOLO_BPM] ?: 120f).toDouble()
                .coerceIn(SoloScheduler.MIN_BPM, SoloScheduler.MAX_BPM),
            soloBeatsPerBar = (prefs[KEY_SOLO_BEATS_PER_BAR] ?: 4)
                .coerceIn(SoloScheduler.MIN_BEATS_PER_BAR, SoloScheduler.MAX_BEATS_PER_BAR),
            soloSubdivision = if (rawSub in SoloScheduler.VALID_SUBDIVISIONS) rawSub else 1
        )
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = url.trim()
        }
    }

    suspend fun saveManualOffsetMs(offsetMs: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MANUAL_OFFSET_MS] = offsetMs.coerceIn(MIN_MANUAL_OFFSET_MS, MAX_MANUAL_OFFSET_MS)
        }
    }

    suspend fun saveMasterVolume(volume: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MASTER_VOLUME] = volume.coerceIn(0f, 1f)
        }
    }

    suspend fun saveClickSound(option: ClickSoundOption) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLICK_SOUND] = option.id
        }
    }

    suspend fun saveVisualBeatFlash(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VISUAL_BEAT_FLASH] = enabled
        }
    }

    suspend fun saveAccentPattern(levels: List<Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCENT_PATTERN] = AccentPattern.serialize(levels)
        }
    }

    suspend fun saveSubdivision(subdivision: Int) {
        if (subdivision !in SoloScheduler.VALID_SUBDIVISIONS) return
        context.dataStore.edit { prefs ->
            prefs[KEY_SOLO_SUBDIVISION] = subdivision
        }
    }

    suspend fun saveSoloTransport(bpm: Double, beatsPerBar: Int, subdivision: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SOLO_BPM] = bpm.toFloat().coerceIn(
                SoloScheduler.MIN_BPM.toFloat(),
                SoloScheduler.MAX_BPM.toFloat()
            )
            prefs[KEY_SOLO_BEATS_PER_BAR] = beatsPerBar.coerceIn(
                SoloScheduler.MIN_BEATS_PER_BAR,
                SoloScheduler.MAX_BEATS_PER_BAR
            )
            prefs[KEY_SOLO_SUBDIVISION] =
                if (subdivision in SoloScheduler.VALID_SUBDIVISIONS) subdivision else 1
        }
    }
}
