package it.parrocchia.coro.domain.model

enum class AppThemeMode(
    val displayLabel: String,
    val description: String
) {
    DARK(
        displayLabel = "Scuro",
        description = "Ideale per la penombra in chiesa (predefinito)"
    ),
    LIGHT(
        displayLabel = "Chiaro",
        description = "Alta leggibilità alla luce del giorno o all'aperto"
    ),
    SYSTEM(
        displayLabel = "Sistema",
        description = "Segue automaticamente il tema di Android"
    );

    companion object {
        fun fromStoredName(raw: String?): AppThemeMode =
            entries.firstOrNull { it.name == raw } ?: DARK
    }
}
