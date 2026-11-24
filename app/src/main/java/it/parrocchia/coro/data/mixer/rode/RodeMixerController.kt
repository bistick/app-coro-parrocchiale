package it.parrocchia.coro.data.mixer.rode

import android.util.Log
import it.parrocchia.coro.domain.mixer.MixerController
import it.parrocchia.coro.domain.mixer.RodeAutomationConfig
import it.parrocchia.coro.domain.mixer.RodeConnectionStatus
import it.parrocchia.coro.domain.mixer.RodeMixerPreset
import it.parrocchia.coro.domain.mixer.RodeMixerPresetCodec
import it.parrocchia.coro.domain.mixer.RodeMixerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Controller USB HID per la gestione dei Muto sul RØDECaster Pro II (con sincronizzazione
 * bidirezionale in tempo reale, protezione dell'uscita casse esterne / Monitor Out e
 * automazione automatica all'avvio e alla fine di ogni brano).
 *
 * Non modifica mai volumi, guadagni o routing custom: invia esclusivamente comandi Muto
 * (`CHANNEL[i].channelOutputMute`, `OUTPUT[0].outputMonMute`, `OUTPUT[0].outputMonAutoMute`)
 * validati dallo Stray Guard di [RodeTreeLayout].
 */
class RodeMixerController(
    private val transport: RodeHidTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val writeSpacingMs: Long = DEFAULT_WRITE_SPACING_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    autoConnectOnInit: Boolean = false
) : MixerController {

    private val ioMutex = Mutex()
    @Volatile
    private var liveLayout: RodeTreeLayout? = null
    private var lastWriteTimestampMs: Long = 0L
    private var pushListenerJob: Job? = null
    private var pendingLayoutRefreshJob: Job? = null

    private val activeTrackUnmutedFaders = mutableSetOf<Int>()
    @Volatile
    private var automationConfig: RodeAutomationConfig = RodeAutomationConfig()

    private val _state = MutableStateFlow(RodeMixerState())
    val state: StateFlow<RodeMixerState> = _state.asStateFlow()

    init {
        transport.startMonitoring(
            onPermissionGranted = {
                scope.launch { connect() }
            },
            onDeviceDetached = {
                scope.launch { handleExternalDisconnect() }
            },
            onDeviceAttached = {
                scope.launch { connectIfDisconnected() }
            }
        )
        if (autoConnectOnInit) {
            scope.launch { connectIfDisconnected() }
        }
    }

    fun triggerAutoConnect() {
        scope.launch { connectIfDisconnected() }
    }

    suspend fun connectIfDisconnected(): Result<Unit> = ioMutex.withLock {
        if (transport.isConnected && liveLayout != null) {
            return@withLock Result.success(Unit)
        }
        connectLocked(silentWhenMissing = true)
    }

    suspend fun connect(): Result<Unit> = ioMutex.withLock {
        connectLocked(silentWhenMissing = false)
    }

    private suspend fun connectLocked(silentWhenMissing: Boolean): Result<Unit> {
        stopPushListener()
        if (!silentWhenMissing) {
            _state.update {
                it.copy(
                    status = RodeConnectionStatus.CONNECTING,
                    isBusy = true,
                    errorMessage = null,
                    statusMessage = "Ricerca RØDECaster Pro II su USB 1..."
                )
            }
        }

        val result = runCatching { transport.connect() }
            .getOrElse { err -> RodeConnectResult.Error(err.message ?: "Errore connessione USB") }

        return when (result) {
            RodeConnectResult.DeviceNotFound -> {
                resetInternalTracking()
                _state.value = RodeMixerState(
                    status = RodeConnectionStatus.DISCONNECTED,
                    automationConfig = automationConfig,
                    statusMessage = "Nessun RØDECaster Pro II rilevato. Collega il cavo alla porta USB 1."
                )
                Result.failure(IllegalStateException("Nessun RØDECaster Pro II rilevato su USB 1"))
            }

            is RodeConnectResult.PermissionRequired -> {
                resetInternalTracking()
                _state.value = RodeMixerState(
                    status = RodeConnectionStatus.PERMISSION_REQUIRED,
                    deviceName = result.deviceLabel,
                    automationConfig = automationConfig,
                    statusMessage = "Concedi il permesso USB per controllare ${result.deviceLabel}."
                )
                Result.failure(IllegalStateException("Permesso USB richiesto per ${result.deviceLabel}"))
            }

            is RodeConnectResult.Error -> {
                resetInternalTracking()
                _state.value = RodeMixerState(
                    status = RodeConnectionStatus.ERROR,
                    automationConfig = automationConfig,
                    errorMessage = result.message
                )
                Result.failure(IllegalStateException(result.message))
            }

            is RodeConnectResult.Connected -> {
                loadModelLocked(fallbackDeviceName = result.deviceLabel)
            }
        }
    }

    suspend fun refresh(): Result<Unit> = ioMutex.withLock {
        if (!transport.isConnected) {
            return@withLock Result.failure(IllegalStateException("RØDECaster Pro II non connesso"))
        }
        _state.update { it.copy(isBusy = true, errorMessage = null, statusMessage = "Lettura canali dal mixer...") }
        loadModelLocked(fallbackDeviceName = _state.value.deviceName ?: "RØDECaster Pro II")
    }

    fun disconnect() {
        stopPushListener()
        transport.disconnect()
        resetInternalTracking()
        _state.value = RodeMixerState(
            status = RodeConnectionStatus.DISCONNECTED,
            automationConfig = automationConfig,
            statusMessage = "Mixer disconnesso"
        )
    }

    fun setAutomationConfig(config: RodeAutomationConfig) {
        automationConfig = config
        _state.update { it.copy(automationConfig = config) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Attiva (`muted = true`) o disattiva (`muted = false`) il Muto su qualsiasi fader (0..8)
     * tramite un singolo pacchetto `CHANNEL[faderIndex].channelOutputMute`.
     */
    suspend fun setChannelMute(faderIndex: Int, muted: Boolean): Result<Boolean> = ioMutex.withLock {
        setChannelMuteLocked(faderIndex, muted)
    }

    /**
     * Attiva (`muted = true`) o disattiva (`muted = false`) il Muto sull'uscita casse esterne
     * (Monitor Out) tramite `OUTPUT[0].outputMonMute`.
     */
    suspend fun setMonitorMute(muted: Boolean): Result<Boolean> = ioMutex.withLock {
        setMonitorMuteLocked(muted)
    }

    /**
     * Attiva (`enabled = true`) o disattiva (`enabled = false`) l'Auto-Mute delle casse esterne
     * quando si apre un microfono (`OUTPUT[0].outputMonAutoMute`).
     */
    suspend fun setMonitorAutoMute(enabled: Boolean): Result<Boolean> = ioMutex.withLock {
        setMonitorAutoMuteLocked(enabled)
    }

    /**
     * Mette in Muto tutti i canali assegnati sul mixer (oppure quelli configurati in automazione).
     */
    suspend fun muteAllAssignedFaders(): Result<Int> = ioMutex.withLock {
        if (!transport.isConnected || liveLayout == null) {
            return@withLock Result.failure(IllegalStateException("RØDECaster Pro II non connesso"))
        }
        val targets = _state.value.assignedFaders
            .map { it.faderIndex }
            .ifEmpty { automationConfig.allConfiguredFaderIndices.toList() }
            .filter { it in 0 until RodeHidProtocol.PRO2_TOTAL_FADER_COUNT }
            .distinct()
            .sorted()

        var mutedCount = 0
        for (idx in targets) {
            if (setChannelMuteLocked(idx, true).isSuccess) {
                mutedCount++
            }
        }
        if (mutedCount > 0) {
            _state.update { it.copy(statusMessage = "Tutti i $mutedCount canali messi in Muto") }
        }
        Result.success(mutedCount)
    }

    // --- MixerController hooks (esecuzione live) ---

    override suspend fun onPhaseSelected(phaseId: Long, mixerConfigJson: String?) = Unit

    override suspend fun onTrackStart(trackId: Long, mixerConfigJson: String?) = ioMutex.withLock {
        val config = automationConfig
        val layout = liveLayout
        Log.d(
            TAG,
            "onTrackStart(trackId=$trackId, connected=${transport.isConnected}, hasLayout=${layout != null}, " +
                "enabled=${config.enabled}, music=${config.musicFaderIndices}, keepMon=${config.keepMonitorSpeakersUnmuted}, " +
                "monMute=${_state.value.monitorMuted}, monAuto=${_state.value.monitorAutoMuteEnabled}, json=$mixerConfigJson)"
        )
        if (!transport.isConnected || layout == null || !config.enabled) return@withLock

        // 1. Prima di aprire eventuali microfoni, disattiva Auto-Mute casse esterne e riattiva Monitor Out
        if (config.keepMonitorSpeakersUnmuted) {
            ensureMonitorSpeakersActiveLocked(layout, forceUnmuteMonitor = true)
        }

        // 2. Toglie il Muto a canale musica + microfoni configurati sul singolo brano
        //    (filtrando solo i fader effettivamente assegnati sul mixer, così non scriviamo su slot vuoti)
        val preset = RodeMixerPresetCodec.decode(mixerConfigJson) ?: RodeMixerPreset()
        val assignedIndices = _state.value.assignedFaders.map { it.faderIndex }.toSet()
        val requestedToUnmute = config.fadersToUnmuteOnStart(preset)
        val toUnmute = if (assignedIndices.isNotEmpty()) {
            requestedToUnmute.filter { it in assignedIndices }
        } else {
            requestedToUnmute
        }
        if (requestedToUnmute.size != toUnmute.size) {
            val skipped = requestedToUnmute.filter { it !in toUnmute }.map { it + 1 }
            Log.d(TAG, "Avvio brano $trackId: ignorati fader non assegnati sul mixer: $skipped")
        }
        synchronized(activeTrackUnmutedFaders) {
            activeTrackUnmutedFaders.clear()
            activeTrackUnmutedFaders.addAll(toUnmute)
        }
        if (toUnmute.isEmpty()) return@withLock

        Log.d(TAG, "Automazione avvio brano $trackId: tolgo Muto a fader ${toUnmute.map { it + 1 }}")
        for (faderIdx in toUnmute) {
            setChannelMuteLocked(faderIdx, muted = false)
        }
    }

    override suspend fun onTrackFade(trackId: Long, fadeOutMs: Int, mixerConfigJson: String?) = Unit

    override suspend fun onTrackStop(trackId: Long, mixerConfigJson: String?) = ioMutex.withLock {
        val config = automationConfig
        val layout = liveLayout
        val openedSnapshot = synchronized(activeTrackUnmutedFaders) {
            val copy = activeTrackUnmutedFaders.toSet()
            activeTrackUnmutedFaders.clear()
            copy
        }
        Log.d(
            TAG,
            "onTrackStop(trackId=$trackId, connected=${transport.isConnected}, hasLayout=${layout != null}, " +
                "enabled=${config.enabled}, music=${config.musicFaderIndices}, alwaysMute=${config.alwaysMuteOnStopFaderIndices}, " +
                "opened=$openedSnapshot, json=$mixerConfigJson)"
        )
        if (!transport.isConnected || layout == null || !config.enabled) return@withLock

        val preset = RodeMixerPresetCodec.decode(mixerConfigJson) ?: RodeMixerPreset()
        val assignedIndices = _state.value.assignedFaders.map { it.faderIndex }.toSet()
        val requestedToMute = config.fadersToMuteOnStop(preset, previouslyOpenedFaders = openedSnapshot)
        val toMute = if (assignedIndices.isNotEmpty()) {
            requestedToMute.filter { it in assignedIndices }
        } else {
            requestedToMute
        }
        if (toMute.isNotEmpty()) {
            Log.d(TAG, "Automazione fine brano $trackId: metto Muto a fader ${toMute.map { it + 1 }}")
            for (faderIdx in toMute) {
                setChannelMuteLocked(faderIdx, muted = true)
            }
        }

        // Assicura che l'uscita casse esterne non resti mai mutata alla fine del brano
        if (config.keepMonitorSpeakersUnmuted && _state.value.monitorMuted == true) {
            ensureMonitorSpeakersActiveLocked(layout, forceUnmuteMonitor = true)
        }
    }

    fun release() {
        transport.stopMonitoring()
        disconnect()
    }

    /**
     * Riceve in tempo reale i frame Report 0x04 dal mixer:
     * - `CHANNEL[strip].channelOutputMute`: sincronizza subito i tasti Muto dal mixer al telefono.
     * - `CHANNEL[strip].channelInputSource`: aggiorna la lista canali se viene riassegnato un fader.
     * - `OUTPUT[0].outputMonMute / outputMonAutoMute / outputMonAutoMuteActive`: monitora lo stato
     *   dell'uscita casse esterne (Monitor Out).
     */
    internal fun handlePushReport(frame: ByteArray): Boolean {
        val layout = liveLayout ?: return false
        val record = RodeHidProtocol.parsePushFrame(frame) ?: return false
        val key = layout.keyAtPath(record.path) ?: return false
        val segs = key.segments

        if (segs.size == 1 &&
            segs[0].first == "CHANNEL" &&
            record.propertyName == RodeHidProtocol.PROP_CHANNEL_OUTPUT_MUTE
        ) {
            val muted = (record.value as? RodeValue.Bool)?.value ?: return false
            val stripIdx = segs[0].second
            applyMuteToState(stripIdx, muted, updateStatusMessage = false)
            return true
        }

        if (segs.size == 1 &&
            segs[0].first == "CHANNEL" &&
            record.propertyName == RodeHidProtocol.PROP_CHANNEL_INPUT_SOURCE
        ) {
            scheduleLayoutRefresh()
            return true
        }

        if (segs.size == 1 && segs[0].first == "OUTPUT" && segs[0].second == 0) {
            val boolVal = (record.value as? RodeValue.Bool)?.value ?: return false
            when (record.propertyName) {
                RodeHidProtocol.PROP_OUTPUT_MON_MUTE -> {
                    Log.d(TAG, "Push OUTPUT[0].outputMonMute = $boolVal")
                    _state.update { it.copy(monitorMuted = boolVal) }
                    return true
                }

                RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE -> {
                    Log.d(TAG, "Push OUTPUT[0].outputMonAutoMute = $boolVal")
                    _state.update { it.copy(monitorAutoMuteEnabled = boolVal) }
                    return true
                }

                RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE_ACTIVE -> {
                    Log.d(TAG, "Push OUTPUT[0].outputMonAutoMuteActive = $boolVal")
                    _state.update { it.copy(monitorAutoMuteActive = boolVal) }
                    return true
                }
            }
        }

        return false
    }

    private suspend fun setChannelMuteLocked(faderIndex: Int, muted: Boolean): Result<Boolean> {
        val layout = liveLayout
        if (!transport.isConnected || layout == null) {
            return Result.failure(IllegalStateException("RØDECaster Pro II non connesso"))
        }

        return runCatching {
            val packet = RodeHidProtocol.buildChannelMuteWritePacket(layout, faderIndex, muted)
            sendPacedLocked(packet)
            applyMuteToState(faderIndex, muted, updateStatusMessage = true)
            muted
        }.onFailure { err ->
            Log.w(TAG, "Errore scrittura Muto su Fader ${faderIndex + 1} (muted=$muted): ${err.message}")
            _state.update { it.copy(errorMessage = err.message ?: "Errore scrittura stato muto") }
        }
    }

    private suspend fun setMonitorMuteLocked(muted: Boolean): Result<Boolean> {
        val layout = liveLayout
        if (!transport.isConnected || layout == null) {
            return Result.failure(IllegalStateException("RØDECaster Pro II non connesso"))
        }

        return runCatching {
            val packet = RodeHidProtocol.buildMonitorMuteWritePacket(layout, muted)
            sendPacedLocked(packet)
            _state.update {
                it.copy(
                    monitorMuted = muted,
                    errorMessage = null,
                    statusMessage = if (muted) "Uscita casse esterne: Muto" else "Uscita casse esterne: Attiva"
                )
            }
            muted
        }.onFailure { err ->
            Log.w(TAG, "Errore scrittura outputMonMute=$muted: ${err.message}")
            _state.update { it.copy(errorMessage = err.message ?: "Errore controllo casse esterne") }
        }
    }

    private suspend fun setMonitorAutoMuteLocked(enabled: Boolean): Result<Boolean> {
        val layout = liveLayout
        if (!transport.isConnected || layout == null) {
            return Result.failure(IllegalStateException("RØDECaster Pro II non connesso"))
        }

        return runCatching {
            val packet = RodeHidProtocol.buildMonitorAutoMuteWritePacket(layout, enabled)
            sendPacedLocked(packet)
            _state.update {
                it.copy(
                    monitorAutoMuteEnabled = enabled,
                    monitorAutoMuteActive = if (!enabled) false else it.monitorAutoMuteActive,
                    errorMessage = null,
                    statusMessage = if (enabled) {
                        "Auto-Muto casse esterne attivo sul mixer"
                    } else {
                        "Auto-Muto casse esterne disattivato"
                    }
                )
            }
            enabled
        }.onFailure { err ->
            Log.w(TAG, "Errore scrittura outputMonAutoMute=$enabled: ${err.message}")
            _state.update { it.copy(errorMessage = err.message ?: "Errore configurazione Auto-Muto casse") }
        }
    }

    /**
     * Impedisce che il mixer silenzi le casse esterne (Monitor Out):
     * 1. Se sul mixer è attivo `outputMonAutoMute` (che silenzia tutte le casse non appena
     *    viene tolto il Muto a un microfono), lo disattiva prima di aprire i canali.
     * 2. Se [forceUnmuteMonitor] è true e l'uscita casse risulta mutata (`outputMonMute != false`),
     *    la riattiva esplicitamente.
     */
    private suspend fun ensureMonitorSpeakersActiveLocked(
        layout: RodeTreeLayout,
        forceUnmuteMonitor: Boolean
    ) {
        val outputKey = RodeHidProtocol.outputKey()
        val current = _state.value
        if (layout.hasProperty(outputKey, RodeHidProtocol.PROP_OUTPUT_MON_AUTO_MUTE) &&
            (current.monitorAutoMuteEnabled == true || current.monitorAutoMuteActive == true)
        ) {
            Log.d(TAG, "Disattivo automaticamente OUTPUT[0].outputMonAutoMute per proteggere le casse esterne")
            setMonitorAutoMuteLocked(enabled = false)
        }
        if (forceUnmuteMonitor &&
            layout.hasProperty(outputKey, RodeHidProtocol.PROP_OUTPUT_MON_MUTE) &&
            current.monitorMuted != false
        ) {
            Log.d(TAG, "Riattivo automaticamente OUTPUT[0].outputMonMute=false (casse esterne)")
            setMonitorMuteLocked(muted = false)
        }
    }

    private fun applyMuteToState(faderIndex: Int, muted: Boolean, updateStatusMessage: Boolean) {
        _state.update { current ->
            val updatedAllFaders = current.faders.map { f ->
                if (f.faderIndex == faderIndex) f.copy(isMuted = muted) else f
            }
            val updatedVirtualFaders = current.virtualFaders.map { vf ->
                if (vf.faderIndex == faderIndex) vf.copy(isMuted = muted) else vf
            }
            val updatedInputs = current.inputs.map { ch ->
                if (ch.assignedFaderIndex == faderIndex) ch.copy(isMuted = muted) else ch
            }
            val nextStatus = if (updateStatusMessage) {
                val faderLabel = updatedAllFaders.firstOrNull { it.faderIndex == faderIndex }?.assignedLabel
                    ?: "Fader ${faderIndex + 1}"
                "Fader ${faderIndex + 1} ($faderLabel): ${if (muted) "Muto" else "Attivo"}"
            } else {
                current.statusMessage
            }
            current.copy(
                inputs = updatedInputs,
                faders = updatedAllFaders,
                virtualFaders = updatedVirtualFaders,
                errorMessage = if (updateStatusMessage) null else current.errorMessage,
                statusMessage = nextStatus
            )
        }
    }

    private fun startPushListener() {
        stopPushListener()
        if (!transport.supportsLivePush) return
        pushListenerJob = scope.launch {
            Log.d(TAG, "Listener push HID avviato")
            while (isActive && transport.isConnected) {
                try {
                    val report = transport.readNextPushReport(PUSH_POLL_TIMEOUT_MS)
                    if (report != null) {
                        handlePushReport(report)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Errore lettura push HID: ${e.message}")
                    delay(PUSH_ERROR_RETRY_DELAY_MS)
                }
            }
            Log.d(TAG, "Listener push HID terminato")
        }
    }

    private fun stopPushListener() {
        pendingLayoutRefreshJob?.cancel()
        pendingLayoutRefreshJob = null
        pushListenerJob?.cancel()
        pushListenerJob = null
    }

    private fun scheduleLayoutRefresh() {
        pendingLayoutRefreshJob?.cancel()
        pendingLayoutRefreshJob = scope.launch {
            delay(LAYOUT_REFRESH_DEBOUNCE_MS)
            if (transport.isConnected) {
                refresh()
            }
        }
    }

    private suspend fun handleExternalDisconnect() = ioMutex.withLock {
        stopPushListener()
        resetInternalTracking()
        _state.value = RodeMixerState(
            status = RodeConnectionStatus.DISCONNECTED,
            automationConfig = automationConfig,
            statusMessage = "RØDECaster Pro II scollegato"
        )
    }

    private fun resetInternalTracking() {
        liveLayout = null
        synchronized(activeTrackUnmutedFaders) {
            activeTrackUnmutedFaders.clear()
        }
    }

    private suspend fun loadModelLocked(fallbackDeviceName: String): Result<Unit> {
        return runCatching {
            val dumpBytes = transport.requestModelDump()
            val root = RodeValueTreeCodec.parse(dumpBytes)
            val layout = RodeTreeLayout(root)
            val snapshot = RodeHidProtocol.parsePro2Snapshot(layout)

            if (snapshot.inputs.isEmpty()) {
                throw IllegalStateException(
                    "Modello del mixer letto ma nessun ingresso RØDECaster Pro II trovato"
                )
            }

            liveLayout = layout

            val nextState = RodeMixerState(
                status = RodeConnectionStatus.CONNECTED,
                deviceName = snapshot.deviceName.ifBlank { fallbackDeviceName },
                firmwareVersion = snapshot.firmwareVersion,
                usbExpandedMode = snapshot.usbExpandedMode,
                inputs = snapshot.inputs,
                virtualFaders = snapshot.virtualFaders,
                faders = snapshot.faders,
                monitorMuted = snapshot.monitorMuted,
                monitorAutoMuteEnabled = snapshot.monitorAutoMuteEnabled,
                monitorAutoMuteActive = snapshot.monitorAutoMuteActive,
                automationConfig = automationConfig,
                errorMessage = null,
                isBusy = false
            )
            val assignedCount = nextState.assignedFaders.size
            val statusSummary = if (assignedCount > 0) {
                "$assignedCount canali attivi · sincronizzazione Muto live"
            } else {
                "Nessun canale assegnato ai fader sul mixer"
            }
            _state.value = nextState.copy(statusMessage = statusSummary)

            val faderLog = nextState.assignedFaders.joinToString(", ") { f ->
                "F${f.displayNumber}=${f.assignedLabel}(mute=${f.isMuted})"
            }
            Log.d(
                TAG,
                "Configurazione canali letta: [$faderLog] | Monitor(mute=${snapshot.monitorMuted}, " +
                    "autoMute=${snapshot.monitorAutoMuteEnabled}, autoMuteActive=${snapshot.monitorAutoMuteActive})"
            )

            // Se sul mixer è attivo Auto-Mute Monitor Output, lo disattiviamo subito così aprire
            // i microfoni non silenzia mai le casse esterne
            if (automationConfig.enabled && automationConfig.keepMonitorSpeakersUnmuted) {
                ensureMonitorSpeakersActiveLocked(layout, forceUnmuteMonitor = false)
            }

            startPushListener()
        }.onFailure { err ->
            Log.w(TAG, "Errore lettura modello Pro II: ${err.message}", err)
            stopPushListener()
            resetInternalTracking()
            transport.disconnect()
            _state.value = RodeMixerState(
                status = RodeConnectionStatus.ERROR,
                deviceName = fallbackDeviceName,
                automationConfig = automationConfig,
                errorMessage = err.message ?: "Errore nella lettura del modello RØDECaster Pro II",
                isBusy = false
            )
        }
    }

    private suspend fun sendPacedLocked(packet: ByteArray) {
        if (writeSpacingMs > 0L && lastWriteTimestampMs > 0L) {
            val elapsed = clockMs() - lastWriteTimestampMs
            if (elapsed in 0 until writeSpacingMs) {
                delay(writeSpacingMs - elapsed)
            }
        }
        transport.writeReport(packet)
        lastWriteTimestampMs = clockMs()
    }

    companion object {
        private const val TAG = "RodeUsb"
        const val DEFAULT_WRITE_SPACING_MS = 60L
        private const val PUSH_POLL_TIMEOUT_MS = 150
        private const val PUSH_ERROR_RETRY_DELAY_MS = 250L
        private const val LAYOUT_REFRESH_DEBOUNCE_MS = 250L
    }
}
