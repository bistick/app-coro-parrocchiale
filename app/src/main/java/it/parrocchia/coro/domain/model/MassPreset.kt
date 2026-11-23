package it.parrocchia.coro.domain.model

enum class MassPresetCategory(val label: String) {
    ORDINARIE("Ordinarie"),
    TEMPI_FORTI("Tempi Forti"),
    TRIDUO_PASQUALE("Triduo Pasquale"),
    CELEBRAZIONI("Celebrazioni")
}

data class PresetCustomPhase(
    val label: String,
    val sortOrder: Int
)

/**
 * Preset liturgico preconfigurato (Rito Romano - Messale Italiano).
 * Attiva le fasi standard appropriate e aggiunge eventuali fasi proprie della celebrazione
 * nel punto liturgico corretto.
 */
data class MassPreset(
    val id: String,
    val name: String,
    val category: MassPresetCategory,
    val subtitle: String,
    val enabledStandardPhases: Set<StandardPhaseType>,
    val customPhases: List<PresetCustomPhase> = emptyList()
) {
    val totalPhasesCount: Int
        get() = enabledStandardPhases.size + customPhases.size

    /**
     * Elenco ordinato dei nomi delle fasi attive (standard + speciali) nell'ordine liturgico.
     */
    fun orderedPhaseLabels(): List<String> {
        val standard = enabledStandardPhases.map { it.defaultOrder to it.displayName }
        val custom = customPhases.map { it.sortOrder to it.label }
        return (standard + custom)
            .sortedBy { it.first }
            .map { it.second }
    }
}

object MassPresetCatalog {

    val FERIALE = MassPreset(
        id = "feriale",
        name = "Messa Feriale",
        category = MassPresetCategory.ORDINARIE,
        subtitle = "Struttura essenziale senza Gloria e Credo",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.FINALE
        )
    )

    val DOMENICALE = MassPreset(
        id = "domenicale",
        name = "Messa Domenicale",
        category = MassPresetCategory.ORDINARIE,
        subtitle = "Festiva standard con Gloria e Agnello di Dio",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.FINALE
        )
    )

    val SOLENNE = MassPreset(
        id = "solenne",
        name = "Messa Solenne",
        category = MassPresetCategory.ORDINARIE,
        subtitle = "Solennità, festa patronale, Cresime e Prime Comunioni",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.ATTO_PENITENZIALE,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.CREDO,
            StandardPhaseType.PREGHIERA_FEDELI,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.ANAMNESI,
            StandardPhaseType.AMEN_SOLENNE,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.DOPO_COMUNIONE,
            StandardPhaseType.FINALE
        )
    )

    val AVVENTO_QUARESIMA = MassPreset(
        id = "avvento_quaresima",
        name = "Avvento / Quaresima",
        category = MassPresetCategory.TEMPI_FORTI,
        subtitle = "Senza Gloria; con Atto Penitenziale e acclamazione al Vangelo",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.ATTO_PENITENZIALE,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.FINALE
        )
    )

    val NATALE = MassPreset(
        id = "natale",
        name = "Messa di Natale",
        category = MassPresetCategory.TEMPI_FORTI,
        subtitle = "Notte o Giorno di Natale con Kalenda e Bacio del Bambino",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.ATTO_PENITENZIALE,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.CREDO,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.ANAMNESI,
            StandardPhaseType.AMEN_SOLENNE,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.DOPO_COMUNIONE,
            StandardPhaseType.FINALE
        ),
        customPhases = listOf(
            PresetCustomPhase("Annuncio della Nascita (Kalenda)", sortOrder = 5),
            PresetCustomPhase("Adorazione / Bacio del Bambino", sortOrder = 175)
        )
    )

    val PALME = MassPreset(
        id = "palme",
        name = "Domenica delle Palme",
        category = MassPresetCategory.TEMPI_FORTI,
        subtitle = "Con Benedizione e Processione delle Palme, senza Gloria",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.FINALE
        ),
        customPhases = listOf(
            PresetCustomPhase("Benedizione e Processione delle Palme", sortOrder = 5)
        )
    )

    val PENTECOSTE_CORPUS_DOMINI = MassPreset(
        id = "pentecoste_corpus_domini",
        name = "Pentecoste / Corpus Domini",
        category = MassPresetCategory.TEMPI_FORTI,
        subtitle = "Solennità con Aspersione e Sequenza (Veni Sancte / Lauda Sion)",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.ASPERSIONE,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.SEQUENZA,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.CREDO,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AMEN_SOLENNE,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.DOPO_COMUNIONE,
            StandardPhaseType.FINALE
        )
    )

    val GIOVEDI_SANTO = MassPreset(
        id = "giovedi_santo",
        name = "Giovedì Santo (In Coena Domini)",
        category = MassPresetCategory.TRIDUO_PASQUALE,
        subtitle = "Triduo: con Gloria, Lavanda dei piedi e Reposizione del SS.mo",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.ATTO_PENITENZIALE,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE
        ),
        customPhases = listOf(
            PresetCustomPhase("Lavanda dei piedi", sortOrder = 85),
            PresetCustomPhase("Reposizione del Santissimo", sortOrder = 175)
        )
    )

    val VENERDI_SANTO = MassPreset(
        id = "venerdi_santo",
        name = "Venerdì Santo (Passione)",
        category = MassPresetCategory.TRIDUO_PASQUALE,
        subtitle = "Triduo: Liturgia della Parola, Adorazione della Croce e Comunione",
        enabledStandardPhases = setOf(
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.COMUNIONE
        ),
        customPhases = listOf(
            PresetCustomPhase("Adorazione della Croce", sortOrder = 95),
            PresetCustomPhase("Improperi / Canto alla Croce", sortOrder = 98)
        )
    )

    val VEGLIA_PASQUALE = MassPreset(
        id = "veglia_pasquale",
        name = "Veglia Pasquale",
        category = MassPresetCategory.TRIDUO_PASQUALE,
        subtitle = "Triduo: Lucernario, Exsultet, Gloria, Litanie dei Santi e Aspersione",
        enabledStandardPhases = setOf(
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.ASPERSIONE,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.ANAMNESI,
            StandardPhaseType.AMEN_SOLENNE,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.DOPO_COMUNIONE,
            StandardPhaseType.FINALE
        ),
        customPhases = listOf(
            PresetCustomPhase("Lucernario e Preconio (Exsultet)", sortOrder = 5),
            PresetCustomPhase("Litanie dei Santi", sortOrder = 75)
        )
    )

    val PASQUA = MassPreset(
        id = "pasqua",
        name = "Domenica di Pasqua",
        category = MassPresetCategory.TRIDUO_PASQUALE,
        subtitle = "Solennità di Risurrezione con Aspersione e Sequenza Pasquale",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.ASPERSIONE,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.SEQUENZA,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AMEN_SOLENNE,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.DOPO_COMUNIONE,
            StandardPhaseType.FINALE
        )
    )

    val MATRIMONIO = MassPreset(
        id = "matrimonio",
        name = "Matrimonio",
        category = MassPresetCategory.CELEBRAZIONI,
        subtitle = "Con Rito del Matrimonio e Canto alle Firme / Ave Maria",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.GLORIA,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE,
            StandardPhaseType.FINALE
        ),
        customPhases = listOf(
            PresetCustomPhase("Rito del Matrimonio / Scambio Anelli", sortOrder = 85),
            PresetCustomPhase("Canto alle Firme / Ave Maria", sortOrder = 175)
        )
    )

    val FUNERALE = MassPreset(
        id = "funerale",
        name = "Esequie / Funerale",
        category = MassPresetCategory.CELEBRAZIONI,
        subtitle = "Senza Gloria, con Ultima Raccomandazione e Commiato",
        enabledStandardPhases = setOf(
            StandardPhaseType.INGRESSO,
            StandardPhaseType.SALMO,
            StandardPhaseType.ALLELUIA,
            StandardPhaseType.OFFERTORIO,
            StandardPhaseType.SANTO,
            StandardPhaseType.AGNELLO_DI_DIO,
            StandardPhaseType.COMUNIONE
        ),
        customPhases = listOf(
            PresetCustomPhase("Ultima Raccomandazione e Commiato", sortOrder = 185)
        )
    )

    val all: List<MassPreset> = listOf(
        FERIALE,
        DOMENICALE,
        SOLENNE,
        AVVENTO_QUARESIMA,
        NATALE,
        PALME,
        PENTECOSTE_CORPUS_DOMINI,
        GIOVEDI_SANTO,
        VENERDI_SANTO,
        VEGLIA_PASQUALE,
        PASQUA,
        MATRIMONIO,
        FUNERALE
    )

    fun findById(id: String?): MassPreset? =
        if (id == null) null else all.firstOrNull { it.id == id }
}
