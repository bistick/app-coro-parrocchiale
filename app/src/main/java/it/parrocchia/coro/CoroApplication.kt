package it.parrocchia.coro

import android.app.Application
import it.parrocchia.coro.audio.AudioOutputRouter
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.data.db.CoroDatabase
import it.parrocchia.coro.data.library.LibraryScanner
import it.parrocchia.coro.data.library.SoundFontManager
import it.parrocchia.coro.data.mixer.rode.AndroidUsbRodeHidTransport
import it.parrocchia.coro.data.mixer.rode.RodeMixerController
import it.parrocchia.coro.data.prefs.AppSettingsStore
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.mixer.MixerController

class CoroApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val database: CoroDatabase = CoroDatabase.getInstance(application)
    val settingsStore: AppSettingsStore = AppSettingsStore(application)
    val soundFontManager: SoundFontManager = SoundFontManager(application)
    val libraryScanner: LibraryScanner = LibraryScanner(application, database.coroDao())
    val repository: CoroRepository = CoroRepository(
        dao = database.coroDao(),
        scanner = libraryScanner,
        settingsStore = settingsStore,
        soundFontManager = soundFontManager
    )
    val audioOutputRouter: AudioOutputRouter = AudioOutputRouter(application)
    val rodeMixerController: RodeMixerController = RodeMixerController(
        transport = AndroidUsbRodeHidTransport(application)
    )
    val mixerController: MixerController = rodeMixerController
    val playbackController: PlaybackController = PlaybackController(
        context = application,
        repository = repository,
        mixerController = mixerController,
        audioOutputRouter = audioOutputRouter
    )
}
