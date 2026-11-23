package it.parrocchia.coro.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.StandardPhaseType
import it.parrocchia.coro.domain.model.TrackSettings

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["title"]),
        Index(value = ["relativeFolder"])
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uri: String,
    val title: String,
    val fileName: String,
    val relativeFolder: String = "",
    val format: String, // AudioFormat.name
    val durationMs: Long = 0L,
    val defaultFadeOutMs: Int = TrackSettings.DEFAULT_FADE_OUT_MS,
    val defaultStartOffsetMs: Long = 0L,
    val defaultTransposeSemitones: Int = 0,
    val defaultVolume: Float = TrackSettings.DEFAULT_VOLUME,
    val isDemo: Boolean = false,
    val lastModifiedMs: Long = 0L
) {
    val audioFormat: AudioFormat
        get() = AudioFormat.fromCode(format)

    val defaultSettings: TrackSettings
        get() = TrackSettings(
            fadeOutMs = defaultFadeOutMs,
            startOffsetMs = defaultStartOffsetMs,
            transposeSemitones = defaultTransposeSemitones,
            volume = defaultVolume
        )
}

@Entity(
    tableName = "mass_plans",
    indices = [Index(value = ["isActive"]), Index(value = ["isTemplate"])]
)
data class MassPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val dateEpochDay: Long,
    val isTemplate: Boolean = false,
    val templateCategory: String? = null,
    val isActive: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "mass_phases",
    foreignKeys = [
        ForeignKey(
            entity = MassPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["massPlanId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["massPlanId"])]
)
data class MassPhaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val massPlanId: Long,
    val phaseType: String, // StandardPhaseType.name
    val customLabel: String? = null,
    val sortOrder: Int,
    val isEnabled: Boolean,
    val mixerConfigJson: String? = null // Predisposizione RØDECaster Pro II
) {
    val standardType: StandardPhaseType
        get() = StandardPhaseType.fromCode(phaseType)

    val displayName: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: standardType.displayName
}

@Entity(
    tableName = "planned_tracks",
    foreignKeys = [
        ForeignKey(
            entity = MassPhaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["phaseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["phaseId"]),
        Index(value = ["songId"])
    ]
)
data class PlannedTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val phaseId: Long,
    val songId: Long,
    val sortOrder: Int,
    val fadeOutMs: Int = TrackSettings.DEFAULT_FADE_OUT_MS,
    val startOffsetMs: Long = 0L,
    val transposeSemitones: Int = 0,
    val volume: Float = TrackSettings.DEFAULT_VOLUME,
    val playedInSession: Boolean = false,
    val mixerConfigJson: String? = null // Predisposizione RØDECaster Pro II
) {
    val settings: TrackSettings
        get() = TrackSettings(
            fadeOutMs = fadeOutMs,
            startOffsetMs = startOffsetMs,
            transposeSemitones = transposeSemitones,
            volume = volume
        )
}

data class PlannedTrackWithSong(
    @Embedded val track: PlannedTrackEntity,
    @Relation(
        parentColumn = "songId",
        entityColumn = "id"
    )
    val song: SongEntity
)

data class PhaseWithTracks(
    @Embedded val phase: MassPhaseEntity,
    @Relation(
        entity = PlannedTrackEntity::class,
        parentColumn = "id",
        entityColumn = "phaseId"
    )
    val tracks: List<PlannedTrackWithSong>
) {
    val orderedTracks: List<PlannedTrackWithSong>
        get() = tracks.sortedBy { it.track.sortOrder }
}

data class MassPlanWithPhases(
    @Embedded val plan: MassPlanEntity,
    @Relation(
        entity = MassPhaseEntity::class,
        parentColumn = "id",
        entityColumn = "massPlanId"
    )
    val phases: List<PhaseWithTracks>
) {
    val orderedPhases: List<PhaseWithTracks>
        get() = phases.sortedBy { it.phase.sortOrder }

    val enabledPhases: List<PhaseWithTracks>
        get() = orderedPhases.filter { it.phase.isEnabled }
}
