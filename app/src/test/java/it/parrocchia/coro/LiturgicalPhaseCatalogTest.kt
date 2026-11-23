package it.parrocchia.coro

import it.parrocchia.coro.audio.PlaybackState
import it.parrocchia.coro.audio.PlaybackStatus
import it.parrocchia.coro.domain.model.MassPresetCatalog
import it.parrocchia.coro.domain.model.StandardPhaseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun massPresets_haveUniqueIdsNonEmptyPhasesAndLookupWorks() {
        val all = MassPresetCatalog.all
        assertTrue(all.size >= 13)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (preset in all) {
            assertTrue("Preset ${preset.id} must have at least one phase", preset.totalPhasesCount > 0)
            assertEquals(preset.totalPhasesCount, preset.orderedPhaseLabels().size)
            assertNotNull(MassPresetCatalog.findById(preset.id))
        }
    }

    @Test
    fun massPresets_followRomanRiteLiturgicalRules() {
        // Feriale e Avvento/Quaresima senza Gloria
        assertFalse(StandardPhaseType.GLORIA in MassPresetCatalog.FERIALE.enabledStandardPhases)
        assertFalse(StandardPhaseType.GLORIA in MassPresetCatalog.AVVENTO_QUARESIMA.enabledStandardPhases)

        // Giovedì Santo: Gloria, Lavanda dei piedi, Reposizione del Santissimo, senza Finale
        val giovediLabels = MassPresetCatalog.GIOVEDI_SANTO.orderedPhaseLabels()
        assertTrue(StandardPhaseType.GLORIA in MassPresetCatalog.GIOVEDI_SANTO.enabledStandardPhases)
        assertFalse(StandardPhaseType.FINALE in MassPresetCatalog.GIOVEDI_SANTO.enabledStandardPhases)
        assertTrue(giovediLabels.contains("Lavanda dei piedi"))
        assertEquals("Reposizione del Santissimo", giovediLabels.last())

        // Venerdì Santo: Passione senza Ingresso, Gloria, Offertorio, Santo, Finale; con Adorazione della Croce
        assertFalse(StandardPhaseType.INGRESSO in MassPresetCatalog.VENERDI_SANTO.enabledStandardPhases)
        assertFalse(StandardPhaseType.GLORIA in MassPresetCatalog.VENERDI_SANTO.enabledStandardPhases)
        assertFalse(StandardPhaseType.OFFERTORIO in MassPresetCatalog.VENERDI_SANTO.enabledStandardPhases)
        assertFalse(StandardPhaseType.SANTO in MassPresetCatalog.VENERDI_SANTO.enabledStandardPhases)
        assertTrue(MassPresetCatalog.VENERDI_SANTO.orderedPhaseLabels().contains("Adorazione della Croce"))

        // Veglia Pasquale: inizia con Lucernario / Exsultet e include Litanie dei Santi
        val vegliaLabels = MassPresetCatalog.VEGLIA_PASQUALE.orderedPhaseLabels()
        assertEquals("Lucernario e Preconio (Exsultet)", vegliaLabels.first())
        assertTrue(vegliaLabels.contains("Litanie dei Santi"))
        assertTrue(StandardPhaseType.ASPERSIONE in MassPresetCatalog.VEGLIA_PASQUALE.enabledStandardPhases)

        // Natale: inizia con Kalenda e include Bacio del Bambino prima del Finale
        val nataleLabels = MassPresetCatalog.NATALE.orderedPhaseLabels()
        assertEquals("Annuncio della Nascita (Kalenda)", nataleLabels.first())
        assertTrue(nataleLabels.contains("Adorazione / Bacio del Bambino"))
        assertEquals(StandardPhaseType.FINALE.displayName, nataleLabels.last())
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
