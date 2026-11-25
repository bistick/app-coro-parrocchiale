package it.parrocchia.coro.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import it.parrocchia.coro.AppContainer
import it.parrocchia.coro.audio.AudioOutputRouter
import it.parrocchia.coro.audio.PlaybackController
import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.data.db.MassPlanWithPhases
import it.parrocchia.coro.data.db.PlannedTrackWithSong
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.data.mixer.rode.RodeMixerController
import it.parrocchia.coro.data.prefs.AppSettings
import it.parrocchia.coro.data.repository.CoroRepository
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeMixerState
import it.parrocchia.coro.domain.model.AppThemeMode
import it.parrocchia.coro.domain.model.AudioOutputRoute
import it.parrocchia.coro.domain.model.AudioOutputStatus
import it.parrocchia.coro.domain.model.MassPreset
import it.parrocchia.coro.domain.model.TrackSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoroViewModel(
    private val repository: CoroRepository,
    val playbackController: PlaybackController,
    private val rodeMixerController: RodeMixerController? = null,
    private val audioOutputRouter: AudioOutputRouter? = playbackController.audioOutputRouter
) : ViewModel() {

    val activeMassPlan: StateFlow<MassPlanWithPhases?> = repository.activeMassPlan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedTemplates: StateFlow<List<MassPlanWithPhases>> = repository.savedTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs: StateFlow<List<SongEntity>> = repository.allSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = repository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val playbackState: StateFlow<PlaybackState> = playbackController.state

    private val fallbackMixerState = MutableStateFlow(RodeMixerState())
    val rodeMixerState: StateFlow<RodeMixerState> = rodeMixerController?.state ?: fallbackMixerState.asStateFlow()

    private val fallbackAudioOutputStatus = MutableStateFlow(AudioOutputStatus())
    val audioOutputStatus: StateFlow<AudioOutputStatus> =
        audioOutputRouter?.status ?: fallbackAudioOutputStatus.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var autoSelectedMusicChannelInSession = false

    init {
        viewModelScope.launch {
            repository.ensureInitialized()
        }
        viewModelScope.launch {
            repository.appSettings.collectLatest { appSettings ->
                audioOutputRouter?.setPreferredRoute(appSettings.audioOutputRoute)
                if (audioOutputRouter == null) {
                    fallbackAudioOutputStatus.value = fallbackAudioOutputStatus.value.copy(
                        selectedRoute = appSettings.audioOutputRoute
                    )
                }
                val skipStalePreAutoSave = autoSelectedMusicChannelInSession &&
                    !appSettings.hasConfiguredRodeMusicChannel &&
                    appSettings.rodeAutomationConfig.musicFaderIndices.isEmpty()
                if (!skipStalePreAutoSave) {
                    rodeMixerController?.setAutomationConfig(appSettings.rodeAutomationConfig)
                    if (rodeMixerController == null) {
                        fallbackMixerState.value = fallbackMixerState.value.copy(
                            automationConfig = appSettings.rodeAutomationConfig
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(repository.appSettings, rodeMixerState) { appSettings, mixerState ->
                appSettings to mixerState
            }.collectLatest { (appSettings, mixerState) ->
                if (!autoSelectedMusicChannelInSession &&
                    !appSettings.hasConfiguredRodeMusicChannel &&
                    appSettings.rodeAutomationConfig.musicFaderIndices.isEmpty() &&
                    mixerState.isConnected &&
                    mixerState.assignedFaders.isNotEmpty()
                ) {
                    val detected = RodeAutomationConfig.detectDefaultMusicFader(
                        assignedFaders = mixerState.assignedFaders,
                        preferBluetooth = appSettings.audioOutputRoute == AudioOutputRoute.BLUETOOTH
                    )
                    if (detected != null) {
                        autoSelectedMusicChannelInSession = true
                        val updatedConfig = appSettings.rodeAutomationConfig.copy(
                            enabled = true,
                            musicFaderIndices = setOf(detected.faderIndex)
                        )
                        rodeMixerController?.setAutomationConfig(updatedConfig)
                        repository.setRodeAutomationConfig(updatedConfig, markChannelsConfigured = true)
                        postToast(
                            "Canale musica configurato: F${detected.displayNumber} · ${detected.assignedLabel}"
                        )
                    }
                }
            }
        }
    }

    fun consumeToast() {
        _toastMessage.value = null
    }

    private fun postToast(msg: String) {
        _toastMessage.value = msg
    }

    // --- Programmazione Messa ---
    fun updateMassTitle(planId: Long, title: String) {
        viewModelScope.launch {
            repository.updateMassTitle(planId, title)
        }
    }

    fun resetMass(keepEnabledPhases: Boolean) {
        viewModelScope.launch {
            playbackController.stopPreviewIfActive()
            playbackController.resetLastLiveTrack()
            repository.resetMassForNewDay(keepEnabledPhases)
            postToast("Scaletta azzerata per la nuova messa")
        }
    }

    fun applyLiturgicalPreset(preset: MassPreset, clearTracks: Boolean) {
        viewModelScope.launch {
            playbackController.stopPreviewIfActive()
            if (clearTracks) {
                playbackController.resetLastLiveTrack()
            }
            repository.applyLiturgicalPreset(preset, clearTracks)
            postToast("Preset applicato: ${preset.name}")
        }
    }

    fun saveCurrentMassAsTemplate(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.saveActivePlanAsTemplate(cleaned)
            }.onSuccess {
                postToast("Preset salvato: $cleaned")
            }.onFailure { err ->
                postToast(err.message ?: "Impossibile salvare il preset")
            }
        }
    }

    fun applySavedTemplate(templateId: Long, templateTitle: String) {
        viewModelScope.launch {
            playbackController.stopPreviewIfActive()
            playbackController.resetLastLiveTrack()
            repository.applySavedTemplate(templateId)
            postToast("Preset caricato: $templateTitle")
        }
    }

    fun deleteSavedTemplate(templateId: Long) {
        viewModelScope.launch {
            repository.deleteSavedTemplate(templateId)
            postToast("Preset eliminato")
        }
    }

    fun togglePhase(phaseId: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.togglePhaseEnabled(phaseId, enabled)
        }
    }

    fun addCustomPhase(planId: Long, label: String) {
        if (label.isBlank()) return
        viewModelScope.launch {
            repository.addCustomPhase(planId, label)
            postToast("Fase \"$label\" aggiunta")
        }
    }

    fun deleteCustomPhase(phaseId: Long) {
        viewModelScope.launch {
            repository.deleteCustomPhase(phaseId)
        }
    }

    fun addSongToPhase(phaseId: Long, song: SongEntity) {
        viewModelScope.launch {
            repository.addSongToPhase(phaseId, song)
            postToast("Aggiunto: ${song.title}")
        }
    }

    fun removeTrack(trackId: Long) {
        viewModelScope.launch {
            repository.removePlannedTrack(trackId)
        }
    }

    fun moveTrack(phaseId: Long, trackId: Long, moveUp: Boolean) {
        viewModelScope.launch {
            repository.moveTrackInPhase(phaseId, trackId, moveUp)
        }
    }

    fun savePlannedTrackSettings(
        trackId: Long,
        songId: Long,
        settings: TrackSettings,
        maxDurationMs: Long,
        saveAsDefault: Boolean
    ) {
        viewModelScope.launch {
            playbackController.stopPreviewIfActive()
            repository.saveTrackSettings(
                trackId = trackId,
                songId = songId,
                settings = settings,
                maxDurationMs = maxDurationMs,
                saveAsSongDefault = saveAsDefault
            )
            postToast(
                if (saveAsDefault) "Impostazioni salvate anche come default del brano"
                else "Impostazioni salvate per questa messa"
            )
        }
    }

    fun saveSongDefaultsOnly(songId: Long, settings: TrackSettings, maxDurationMs: Long) {
        viewModelScope.launch {
            playbackController.stopPreviewIfActive()
            repository.updateSongDefaultsDirect(songId, settings, maxDurationMs)
            postToast("Preferenze predefinite salvate e sincronizzate con la messa")
        }
    }

    // --- Libreria SAF ---
    fun selectLibraryFolder(treeUri: Uri, displayName: String?) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val summary = repository.scanUserLibraryFolder(treeUri, displayName)
                postToast("Libreria aggiornata: ${summary.totalIndexed} brani trovati")
            } catch (e: Exception) {
                postToast("Errore lettura cartella: ${e.message ?: "riprova"}")
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun rescanLibraryFolder() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val res = repository.rescanCurrentLibraryFolder()
                res.onSuccess { summary ->
                    postToast("Scansione completata: ${summary.totalIndexed} brani (+${summary.addedCount} nuovi)")
                }.onFailure { err ->
                    postToast(err.message ?: "Seleziona prima una cartella libreria")
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun ensureDemoTracks() {
        viewModelScope.launch {
            repository.reloadDemoSongs()
            postToast("Brani di prova pronti in libreria")
        }
    }

    // --- Impostazioni & SoundFont ---
    fun importCustomSoundFont(uri: Uri) {
        viewModelScope.launch {
            val res = repository.importCustomSoundFont(uri)
            res.onSuccess { name ->
                postToast("SoundFont attivo: $name")
            }.onFailure { err ->
                postToast(err.message ?: "File SoundFont non valido")
            }
        }
    }

    fun resetSoundFont() {
        viewModelScope.launch {
            repository.resetToBundledSoundFont()
            postToast("Ripristinato banco suoni General MIDI integrato")
        }
    }

    fun setHoldDurationMs(ms: Int) {
        viewModelScope.launch { repository.setHoldDurationMs(ms) }
    }

    fun setDefaultFadeOutMs(ms: Int) {
        viewModelScope.launch { repository.setDefaultFadeOutMs(ms) }
    }

    fun setKeepScreenOnInLive(enabled: Boolean) {
        viewModelScope.launch { repository.setKeepScreenOnInLive(enabled) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setAudioOutputRoute(route: AudioOutputRoute) {
        audioOutputRouter?.setPreferredRoute(route)
        viewModelScope.launch {
            repository.setAudioOutputRoute(route)
            postToast("Uscita audio: ${route.displayLabel}")
        }
    }

    fun refreshAudioOutputDevices() {
        audioOutputRouter?.refreshDevices()
    }

    // --- Esecuzione Live ---
    fun playLiveTrack(phaseLabel: String, item: PlannedTrackWithSong) {
        playbackController.playLiveTrack(phaseLabel, item)
    }

    fun fadeOutLive() {
        playbackController.fadeOutCurrent()
    }

    fun stopImmediateLive() {
        playbackController.stopImmediateCurrent()
    }

    fun resetSessionPlayed(planId: Long) {
        viewModelScope.launch {
            playbackController.resetLastLiveTrack()
            repository.resetSessionProgress(planId)
            postToast("Stato brani eseguiti azzerato")
        }
    }

    fun dismissPlaybackError() {
        playbackController.clearError()
    }

    // --- Mixer RØDECaster Pro II ---
    fun connectRodeMixer() {
        val controller = rodeMixerController ?: return
        viewModelScope.launch {
            controller.connect()
        }
    }

    fun refreshRodeMixer() {
        val controller = rodeMixerController ?: return
        viewModelScope.launch {
            controller.refresh()
        }
    }

    fun disconnectRodeMixer() {
        rodeMixerController?.disconnect()
    }

    fun setRodeChannelMute(faderIndex: Int, muted: Boolean) {
        val controller = rodeMixerController ?: return
        viewModelScope.launch {
            controller.setChannelMute(faderIndex, muted)
        }
    }

    fun setRodeMonitorMute(muted: Boolean) {
        val controller = rodeMixerController ?: return
        viewModelScope.launch {
            controller.setMonitorMute(muted)
        }
    }

    fun setRodeMonitorAutoMute(enabled: Boolean) {
        val controller = rodeMixerController ?: return
        viewModelScope.launch {
            controller.setMonitorAutoMute(enabled)
        }
    }

    fun setRodeAutomationConfig(config: RodeAutomationConfig) {
        rodeMixerController?.setAutomationConfig(config)
        if (rodeMixerController == null) {
            fallbackMixerState.value = fallbackMixerState.value.copy(automationConfig = config)
        }
        val markConfigured = rodeMixerState.value.isConnected ||
            config.musicFaderIndices.isNotEmpty() ||
            config.alwaysMuteOnStopFaderIndices.isNotEmpty()
        viewModelScope.launch {
            repository.setRodeAutomationConfig(config, markChannelsConfigured = markConfigured)
        }
    }

    fun muteAllRodeFaders() {
        val controller = rodeMixerController ?: return
        viewModelScope.launch {
            controller.muteAllAssignedFaders().onSuccess { count ->
                if (count > 0) {
                    postToast("Tutti i $count canali messi in Muto")
                }
            }
        }
    }

    fun dismissRodeMixerError() {
        rodeMixerController?.clearError()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CoroViewModel(
                repository = container.repository,
                playbackController = container.playbackController,
                rodeMixerController = container.rodeMixerController,
                audioOutputRouter = container.audioOutputRouter
            ) as T
        }
    }
}
