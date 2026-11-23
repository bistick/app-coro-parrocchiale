package it.parrocchia.coro.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "coro_settings")

data class AppSettings(
    val libraryFolderUri: String? = null,
    val libraryFolderName: String? = null,
    val customSoundFontName: String? = null,
    val customSoundFontLocalPath: String? = null,
    val holdDurationMs: Int = DEFAULT_HOLD_DURATION_MS,
    val defaultFadeOutMs: Int = TrackSettings.DEFAULT_FADE_OUT_MS,
    val keepScreenOnInLive: Boolean = true
) {
    companion object {
        const val MIN_HOLD_DURATION_MS = 300
        const val MAX_HOLD_DURATION_MS = 800
        const val DEFAULT_HOLD_DURATION_MS = 450
    }
}

class AppSettingsStore(private val context: Context) {

    private object Keys {
        val LIBRARY_FOLDER_URI = stringPreferencesKey("library_folder_uri")
        val LIBRARY_FOLDER_NAME = stringPreferencesKey("library_folder_name")
        val CUSTOM_SF2_NAME = stringPreferencesKey("custom_sf2_name")
        val CUSTOM_SF2_LOCAL_PATH = stringPreferencesKey("custom_sf2_local_path")
        val HOLD_DURATION_MS = intPreferencesKey("hold_duration_ms")
        val DEFAULT_FADE_OUT_MS = intPreferencesKey("default_fade_out_ms")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_live")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            libraryFolderUri = prefs[Keys.LIBRARY_FOLDER_URI],
            libraryFolderName = prefs[Keys.LIBRARY_FOLDER_NAME],
            customSoundFontName = prefs[Keys.CUSTOM_SF2_NAME],
            customSoundFontLocalPath = prefs[Keys.CUSTOM_SF2_LOCAL_PATH],
            holdDurationMs = (prefs[Keys.HOLD_DURATION_MS] ?: AppSettings.DEFAULT_HOLD_DURATION_MS)
                .coerceIn(AppSettings.MIN_HOLD_DURATION_MS, AppSettings.MAX_HOLD_DURATION_MS),
            defaultFadeOutMs = (prefs[Keys.DEFAULT_FADE_OUT_MS] ?: TrackSettings.DEFAULT_FADE_OUT_MS)
                .coerceIn(TrackSettings.MIN_FADE_OUT_MS, TrackSettings.MAX_FADE_OUT_MS),
            keepScreenOnInLive = prefs[Keys.KEEP_SCREEN_ON] ?: true
        )
    }

    suspend fun setLibraryFolder(uri: String?, name: String?) {
        context.settingsDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(Keys.LIBRARY_FOLDER_URI)
                prefs.remove(Keys.LIBRARY_FOLDER_NAME)
            } else {
                prefs[Keys.LIBRARY_FOLDER_URI] = uri
                prefs[Keys.LIBRARY_FOLDER_NAME] = name ?: "Cartella selezionata"
            }
        }
    }

    suspend fun setCustomSoundFont(localPath: String?, displayName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (localPath.isNullOrBlank()) {
                prefs.remove(Keys.CUSTOM_SF2_LOCAL_PATH)
                prefs.remove(Keys.CUSTOM_SF2_NAME)
            } else {
                prefs[Keys.CUSTOM_SF2_LOCAL_PATH] = localPath
                prefs[Keys.CUSTOM_SF2_NAME] = displayName ?: "SoundFont personalizzato"
            }
        }
    }

    suspend fun setHoldDurationMs(durationMs: Int) {
        val clamped = durationMs.coerceIn(AppSettings.MIN_HOLD_DURATION_MS, AppSettings.MAX_HOLD_DURATION_MS)
        context.settingsDataStore.edit { it[Keys.HOLD_DURATION_MS] = clamped }
    }

    suspend fun setDefaultFadeOutMs(fadeMs: Int) {
        val clamped = fadeMs.coerceIn(TrackSettings.MIN_FADE_OUT_MS, TrackSettings.MAX_FADE_OUT_MS)
        context.settingsDataStore.edit { it[Keys.DEFAULT_FADE_OUT_MS] = clamped }
    }

    suspend fun setKeepScreenOnInLive(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }
}
