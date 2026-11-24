package it.parrocchia.coro.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeAutomationConfigCodec
import it.parrocchia.coro.domain.model.AudioOutputRoute
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
    val keepScreenOnInLive: Boolean = true,
    val audioOutputRoute: AudioOutputRoute = AudioOutputRoute.AUTO,
    val rodeAutomationConfig: RodeAutomationConfig = RodeAutomationConfig(),
    val hasConfiguredRodeMusicChannel: Boolean = false
) {
    companion object {
        const val MIN_HOLD_DURATION_MS = 300
        const val MAX_HOLD_DURATION_MS = 800
        const val DEFAULT_HOLD_DURATION_MS = 450
    }
}

class AppSettingsStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val LIBRARY_FOLDER_URI = stringPreferencesKey("library_folder_uri")
        val LIBRARY_FOLDER_NAME = stringPreferencesKey("library_folder_name")
        val CUSTOM_SF2_NAME = stringPreferencesKey("custom_sf2_name")
        val CUSTOM_SF2_LOCAL_PATH = stringPreferencesKey("custom_sf2_local_path")
        val HOLD_DURATION_MS = intPreferencesKey("hold_duration_ms")
        val DEFAULT_FADE_OUT_MS = intPreferencesKey("default_fade_out_ms")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_live")
        val AUDIO_OUTPUT_ROUTE = stringPreferencesKey("audio_output_route")
        val RODE_AUTOMATION_JSON = stringPreferencesKey("rode_automation_json")
        val RODE_MUSIC_CHANNEL_CONFIGURED = booleanPreferencesKey("rode_music_channel_configured")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val decodedRodeConfig = RodeAutomationConfigCodec.decode(prefs[Keys.RODE_AUTOMATION_JSON])
        AppSettings(
            libraryFolderUri = prefs[Keys.LIBRARY_FOLDER_URI],
            libraryFolderName = prefs[Keys.LIBRARY_FOLDER_NAME],
            customSoundFontName = prefs[Keys.CUSTOM_SF2_NAME],
            customSoundFontLocalPath = prefs[Keys.CUSTOM_SF2_LOCAL_PATH],
            holdDurationMs = (prefs[Keys.HOLD_DURATION_MS] ?: AppSettings.DEFAULT_HOLD_DURATION_MS)
                .coerceIn(AppSettings.MIN_HOLD_DURATION_MS, AppSettings.MAX_HOLD_DURATION_MS),
            defaultFadeOutMs = (prefs[Keys.DEFAULT_FADE_OUT_MS] ?: TrackSettings.DEFAULT_FADE_OUT_MS)
                .coerceIn(TrackSettings.MIN_FADE_OUT_MS, TrackSettings.MAX_FADE_OUT_MS),
            keepScreenOnInLive = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            audioOutputRoute = AudioOutputRoute.fromStoredName(prefs[Keys.AUDIO_OUTPUT_ROUTE]),
            rodeAutomationConfig = decodedRodeConfig,
            hasConfiguredRodeMusicChannel = (prefs[Keys.RODE_MUSIC_CHANNEL_CONFIGURED] == true) ||
                decodedRodeConfig.musicFaderIndices.isNotEmpty() ||
                decodedRodeConfig.alwaysMuteOnStopFaderIndices.isNotEmpty()
        )
    }

    suspend fun setLibraryFolder(uri: String?, name: String?) {
        dataStore.edit { prefs ->
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
        dataStore.edit { prefs ->
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
        dataStore.edit { it[Keys.HOLD_DURATION_MS] = clamped }
    }

    suspend fun setDefaultFadeOutMs(fadeMs: Int) {
        val clamped = fadeMs.coerceIn(TrackSettings.MIN_FADE_OUT_MS, TrackSettings.MAX_FADE_OUT_MS)
        dataStore.edit { it[Keys.DEFAULT_FADE_OUT_MS] = clamped }
    }

    suspend fun setKeepScreenOnInLive(enabled: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setAudioOutputRoute(route: AudioOutputRoute) {
        dataStore.edit { it[Keys.AUDIO_OUTPUT_ROUTE] = route.name }
    }

    suspend fun setRodeAutomationConfig(
        config: RodeAutomationConfig,
        markChannelsConfigured: Boolean = true
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.RODE_AUTOMATION_JSON] = RodeAutomationConfigCodec.encode(config)
            if (markChannelsConfigured ||
                config.musicFaderIndices.isNotEmpty() ||
                config.alwaysMuteOnStopFaderIndices.isNotEmpty()
            ) {
                prefs[Keys.RODE_MUSIC_CHANNEL_CONFIGURED] = true
            }
        }
    }
}
