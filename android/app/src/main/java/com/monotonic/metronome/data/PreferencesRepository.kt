package com.monotonic.metronome.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "monotonic_prefs")

class PreferencesRepository(private val context: Context) {

    companion object {
        const val DEFAULT_SERVER_URL = "ws://192.168.1.100:8000/ws"
        const val MIN_MANUAL_OFFSET_MS = -50
        const val MAX_MANUAL_OFFSET_MS = 50

        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_MANUAL_OFFSET_MS = intPreferencesKey("manual_offset_ms")
    }

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val manualOffsetMsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_MANUAL_OFFSET_MS] ?: 0).coerceIn(MIN_MANUAL_OFFSET_MS, MAX_MANUAL_OFFSET_MS)
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
}
