package it.parrocchia.coro

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.data.prefs.AppSettingsStore
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStore(name: String): AppSettingsStore {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") }
        )
        return AppSettingsStore(dataStore)
    }

    @Test
    fun defaults_matchExpectedValues() = runTest {
        val store = createStore("defaults")
        val settings = store.settings.first()
        assertNull(settings.libraryFolderUri)
        assertNull(settings.libraryFolderName)
        assertNull(settings.customSoundFontName)
        assertNull(settings.customSoundFontLocalPath)
        assertEquals(AppSettings.DEFAULT_HOLD_DURATION_MS, settings.holdDurationMs)
        assertEquals(TrackSettings.DEFAULT_FADE_OUT_MS, settings.defaultFadeOutMs)
        assertTrue(settings.keepScreenOnInLive)
    }

    @Test
    fun libraryFolder_setsFallbackNameAndClearsWhenBlankOrNull() = runTest {
        val store = createStore("folder")

        store.setLibraryFolder("content://tree/canti", "Canti Parrocchia")
        var s = store.settings.first()
        assertEquals("content://tree/canti", s.libraryFolderUri)
        assertEquals("Canti Parrocchia", s.libraryFolderName)

        store.setLibraryFolder("content://tree/altri", null)
        s = store.settings.first()
        assertEquals("content://tree/altri", s.libraryFolderUri)
        assertEquals("Cartella selezionata", s.libraryFolderName)

        store.setLibraryFolder("   ", "Ignored")
        s = store.settings.first()
        assertNull(s.libraryFolderUri)
        assertNull(s.libraryFolderName)

        store.setLibraryFolder(null, null)
        s = store.settings.first()
        assertNull(s.libraryFolderUri)
    }

    @Test
    fun customSoundFont_setsFallbackNameAndClearsWhenBlankOrNull() = runTest {
        val store = createStore("sf2")

        store.setCustomSoundFont("/data/sf/FluidR3.sf2", "FluidR3_GM.sf2")
        var s = store.settings.first()
        assertEquals("/data/sf/FluidR3.sf2", s.customSoundFontLocalPath)
        assertEquals("FluidR3_GM.sf2", s.customSoundFontName)

        store.setCustomSoundFont("/data/sf/custom.sf3", null)
        s = store.settings.first()
        assertEquals("/data/sf/custom.sf3", s.customSoundFontLocalPath)
        assertEquals("SoundFont personalizzato", s.customSoundFontName)

        store.setCustomSoundFont("", "Ignored")
        s = store.settings.first()
        assertNull(s.customSoundFontLocalPath)
        assertNull(s.customSoundFontName)

        store.setCustomSoundFont(null, null)
        s = store.settings.first()
        assertNull(s.customSoundFontLocalPath)
    }

    @Test
    fun holdDurationFadeOutAndKeepScreenOn_clampAndPersist() = runTest {
        val store = createStore("tuning")

        store.setHoldDurationMs(50)
        assertEquals(AppSettings.MIN_HOLD_DURATION_MS, store.settings.first().holdDurationMs)
        store.setHoldDurationMs(9999)
        assertEquals(AppSettings.MAX_HOLD_DURATION_MS, store.settings.first().holdDurationMs)
        store.setHoldDurationMs(600)
        assertEquals(600, store.settings.first().holdDurationMs)

        store.setDefaultFadeOutMs(100)
        assertEquals(TrackSettings.MIN_FADE_OUT_MS, store.settings.first().defaultFadeOutMs)
        store.setDefaultFadeOutMs(99999)
        assertEquals(TrackSettings.MAX_FADE_OUT_MS, store.settings.first().defaultFadeOutMs)
        store.setDefaultFadeOutMs(4500)
        assertEquals(4500, store.settings.first().defaultFadeOutMs)

        store.setKeepScreenOnInLive(false)
        assertFalse(store.settings.first().keepScreenOnInLive)
        store.setKeepScreenOnInLive(true)
        assertTrue(store.settings.first().keepScreenOnInLive)

        assertEquals(
            it.parrocchia.coro.domain.model.AppThemeMode.DARK,
            store.settings.first().themeMode
        )
        store.setThemeMode(it.parrocchia.coro.domain.model.AppThemeMode.LIGHT)
        assertEquals(
            it.parrocchia.coro.domain.model.AppThemeMode.LIGHT,
            store.settings.first().themeMode
        )
        store.setThemeMode(it.parrocchia.coro.domain.model.AppThemeMode.SYSTEM)
        assertEquals(
            it.parrocchia.coro.domain.model.AppThemeMode.SYSTEM,
            store.settings.first().themeMode
        )

        assertEquals(
            it.parrocchia.coro.domain.model.AudioOutputRoute.AUTO,
            store.settings.first().audioOutputRoute
        )
        store.setAudioOutputRoute(it.parrocchia.coro.domain.model.AudioOutputRoute.BLUETOOTH)
        assertEquals(
            it.parrocchia.coro.domain.model.AudioOutputRoute.BLUETOOTH,
            store.settings.first().audioOutputRoute
        )
        store.setAudioOutputRoute(it.parrocchia.coro.domain.model.AudioOutputRoute.USB)
        assertEquals(
            it.parrocchia.coro.domain.model.AudioOutputRoute.USB,
            store.settings.first().audioOutputRoute
        )

        assertFalse(store.settings.first().hasConfiguredRodeMusicChannel)
        store.setRodeAutomationConfig(
            it.parrocchia.coro.domain.mixer.RodeAutomationConfig(),
            markChannelsConfigured = false
        )
        assertFalse(store.settings.first().hasConfiguredRodeMusicChannel)

        val autoCfg = it.parrocchia.coro.domain.mixer.RodeAutomationConfig(
            enabled = true,
            musicFaderIndices = setOf(6),
            alwaysMuteOnStopFaderIndices = setOf(0, 1)
        )
        store.setRodeAutomationConfig(autoCfg)
        assertEquals(autoCfg, store.settings.first().rodeAutomationConfig)
        assertTrue(store.settings.first().hasConfiguredRodeMusicChannel)
    }

    @Test
    fun contextConstructor_readsDefaultSettings() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AppSettingsStore(context)
        val settings = store.settings.first()
        assertEquals(AppSettings.DEFAULT_HOLD_DURATION_MS, settings.holdDurationMs)
    }
}
