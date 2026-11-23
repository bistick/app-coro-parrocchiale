package it.parrocchia.coro.domain.model

enum class LiturgicalSection(val label: String) {
    RITI_INTRODUZIONE("Riti di Introduzione"),
    LITURGIA_PAROLA("Liturgia della Parola"),
    LITURGIA_EUCARISTICA("Liturgia Eucaristica"),
    RITI_CONCLUSIONE("Riti di Conclusione"),
    ALTRO("Altre fasi")
}

/**
 * Catalogo completo delle fasi della Santa Messa (Rito Romano - Messale Italiano).
 */
enum class StandardPhaseType(
    val displayName: String,
    val section: LiturgicalSection,
    val defaultEnabled: Boolean,
    val defaultOrder: Int,
    val hint: String = ""
) {
    INGRESSO(
        displayName = "Canto d'Ingresso",
        section = LiturgicalSection.RITI_INTRODUZIONE,
        defaultEnabled = true,
        defaultOrder = 10,
        hint = "Processione d'ingresso"
    ),
    ASPERSIONE(
        displayName = "Aspersione",
        section = LiturgicalSection.RITI_INTRODUZIONE,
        defaultEnabled = false,
        defaultOrder = 20,
        hint = "Tempo di Pasqua o solennità"
    ),
    ATTO_PENITENZIALE(
        displayName = "Atto Penitenziale / Kyrie",
        section = LiturgicalSection.RITI_INTRODUZIONE,
        defaultEnabled = false,
        defaultOrder = 30,
        hint = "Signore pietà (se cantato)"
    ),
    GLORIA(
        displayName = "Gloria",
        section = LiturgicalSection.RITI_INTRODUZIONE,
        defaultEnabled = true,
        defaultOrder = 40,
        hint = "Domeniche fuori Avvento/Quaresima e solennità"
    ),
    SALMO(
        displayName = "Salmo Responsoriale",
        section = LiturgicalSection.LITURGIA_PAROLA,
        defaultEnabled = true,
        defaultOrder = 50,
        hint = "Ritornello o salmo intero"
    ),
    SEQUENZA(
        displayName = "Sequenza",
        section = LiturgicalSection.LITURGIA_PAROLA,
        defaultEnabled = false,
        defaultOrder = 60,
        hint = "Pasqua, Pentecoste, Corpus Domini"
    ),
    ALLELUIA(
        displayName = "Acclamazione al Vangelo",
        section = LiturgicalSection.LITURGIA_PAROLA,
        defaultEnabled = true,
        defaultOrder = 70,
        hint = "Alleluia (o Lode e onore in Quaresima)"
    ),
    CREDO(
        displayName = "Credo",
        section = LiturgicalSection.LITURGIA_PAROLA,
        defaultEnabled = false,
        defaultOrder = 80,
        hint = "Professione di fede (se cantata)"
    ),
    PREGHIERA_FEDELI(
        displayName = "Preghiera dei Fedeli",
        section = LiturgicalSection.LITURGIA_PAROLA,
        defaultEnabled = false,
        defaultOrder = 90,
        hint = "Eventuale ritornello cantato"
    ),
    OFFERTORIO(
        displayName = "Offertorio",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = true,
        defaultOrder = 100,
        hint = "Presentazione dei doni"
    ),
    SANTO(
        displayName = "Santo (Sanctus)",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = true,
        defaultOrder = 110,
        hint = "Acclamazione della Preghiera Eucaristica"
    ),
    ANAMNESI(
        displayName = "Acclamazione all'Anamnesi",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = false,
        defaultOrder = 120,
        hint = "Mistero della fede (se cantata)"
    ),
    AMEN_SOLENNE(
        displayName = "Amen solenne",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = false,
        defaultOrder = 130,
        hint = "Dossologia finale"
    ),
    PADRE_NOSTRO(
        displayName = "Padre Nostro",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = false,
        defaultOrder = 140,
        hint = "Se cantato"
    ),
    AGNELLO_DI_DIO(
        displayName = "Agnello di Dio",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = false,
        defaultOrder = 150,
        hint = "Frazione del pane (Agnus Dei)"
    ),
    COMUNIONE(
        displayName = "Comunione",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = true,
        defaultOrder = 160,
        hint = "Supporta più brani avviati manualmente"
    ),
    DOPO_COMUNIONE(
        displayName = "Dopo la Comunione",
        section = LiturgicalSection.LITURGIA_EUCARISTICA,
        defaultEnabled = false,
        defaultOrder = 170,
        hint = "Canto di ringraziamento"
    ),
    FINALE(
        displayName = "Canto Finale / Congedo",
        section = LiturgicalSection.RITI_CONCLUSIONE,
        defaultEnabled = true,
        defaultOrder = 180,
        hint = "Congedo o canto mariano"
    ),
    CUSTOM(
        displayName = "Fase personalizzata",
        section = LiturgicalSection.ALTRO,
        defaultEnabled = false,
        defaultOrder = 200,
        hint = "Fase aggiuntiva libera"
    );

    companion object {
        val standardPhases: List<StandardPhaseType> =
            entries.filter { it != CUSTOM }.sortedBy { it.defaultOrder }

        fun fromCode(code: String): StandardPhaseType =
            entries.firstOrNull { it.name == code } ?: CUSTOM
    }
}
