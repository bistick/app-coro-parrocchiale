package it.parrocchia.coro.domain.mixer

/**
 * Interfaccia di predisposizione per il futuro controllo di mixer esterni
 * (es. RØDECaster Pro II via USB MIDI o rete per volumi microfono solista/coro).
 *
 * In versione 1 viene utilizzata l'implementazione NoOpMixerController.
 */
interface MixerController {
    suspend fun onPhaseSelected(phaseId: Long, mixerConfigJson: String?)
    suspend fun onTrackStart(trackId: Long, mixerConfigJson: String?)
    suspend fun onTrackFade(trackId: Long, fadeOutMs: Int, mixerConfigJson: String?)
    suspend fun onTrackStop(trackId: Long, mixerConfigJson: String?)
}

class NoOpMixerController : MixerController {
    override suspend fun onPhaseSelected(phaseId: Long, mixerConfigJson: String?) = Unit
    override suspend fun onTrackStart(trackId: Long, mixerConfigJson: String?) = Unit
    override suspend fun onTrackFade(trackId: Long, fadeOutMs: Int, mixerConfigJson: String?) = Unit
    override suspend fun onTrackStop(trackId: Long, mixerConfigJson: String?) = Unit
}
