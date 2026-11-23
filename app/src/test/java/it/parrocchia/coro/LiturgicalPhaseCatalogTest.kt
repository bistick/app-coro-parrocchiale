package it.parrocchia.coro

import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.domain.model.StandardPhaseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiturgicalPhaseCatalogTest {

    @Test
    fun standardPhases_containsAll18RomanRitePhasesInLiturgicalOrder() {
        val phases = StandardPhaseType.standardPhases
        assertEquals(18, phases.size)
        assertEquals(StandardPhaseType.INGRESSO, phases.first())
        assertEquals(StandardPhaseType.FINALE, phases.last())

        val orders = phases.map { it.defaultOrder }
        assertEquals(orders.sorted(), orders)
    }

    @Test
    fun defaultSundayMass_hasExpectedCorePhasesEnabled() {
        val defaults = StandardPhaseType.standardPhases.filter { it.defaultEnabled }.toSet()
        assertTrue(StandardPhaseType.INGRESSO in defaults)
        assertTrue(StandardPhaseType.GLORIA in defaults)
        assertTrue(StandardPhaseType.SALMO in defaults)
        assertTrue(StandardPhaseType.ALLELUIA in defaults)
        assertTrue(StandardPhaseType.OFFERTORIO in defaults)
        assertTrue(StandardPhaseType.SANTO in defaults)
        assertTrue(StandardPhaseType.COMUNIONE in defaults)
        assertTrue(StandardPhaseType.FINALE in defaults)
        assertFalse(StandardPhaseType.CREDO in defaults)
        assertFalse(StandardPhaseType.SEQUENZA in defaults)
    }

    @Test
    fun playbackState_tracksBusyAndProgressCorrectly() {
        val idle = PlaybackState()
        assertFalse(idle.isBusy)
        assertEquals(0f, idle.progressFraction, 1e-5f)

        val playing = PlaybackState(
            status = PlaybackStatus.PLAYING,
            positionMs = 15_000L,
            durationMs = 60_000L
        )
        assertTrue(playing.isBusy)
        assertEquals(0.25f, playing.progressFraction, 1e-5f)

        val fading = playing.copy(status = PlaybackStatus.FADING_OUT)
        assertTrue(fading.isBusy)
    }
}
