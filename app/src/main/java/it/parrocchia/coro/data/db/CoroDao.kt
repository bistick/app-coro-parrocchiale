package it.parrocchia.coro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CoroDao {

    // --- Songs ---
    @Query("SELECT * FROM songs ORDER BY isDemo ASC, relativeFolder ASC, title COLLATE NOCASE ASC")
    fun observeAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsOnce(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE uri = :uri LIMIT 1")
    suspend fun getSongByUri(uri: String): SongEntity?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun countSongs(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: SongEntity): Long

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query(
        """
        UPDATE songs
        SET defaultFadeOutMs = :fadeOutMs,
            defaultStartOffsetMs = :startOffsetMs,
            defaultTransposeSemitones = :transposeSemitones,
            defaultVolume = :volume
        WHERE id = :songId
        """
    )
    suspend fun updateSongDefaults(
        songId: Long,
        fadeOutMs: Int,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float
    )

    @Query("UPDATE songs SET defaultMixerConfigJson = :mixerConfigJson WHERE id = :songId")
    suspend fun updateSongDefaultMixerConfig(songId: Long, mixerConfigJson: String?)

    @Query("DELETE FROM songs WHERE isDemo = 0 AND uri NOT IN (:activeUris)")
    suspend fun deleteMissingNonDemoSongs(activeUris: List<String>)

    @Query("DELETE FROM songs WHERE isDemo = 0")
    suspend fun deleteAllNonDemoSongs()

    // --- Mass Plans & Saved Presets ---
    @Transaction
    @Query("SELECT * FROM mass_plans WHERE isActive = 1 AND isTemplate = 0 LIMIT 1")
    fun observeActiveMassPlan(): Flow<MassPlanWithPhases?>

    @Transaction
    @Query("SELECT * FROM mass_plans WHERE isActive = 1 AND isTemplate = 0 LIMIT 1")
    suspend fun getActiveMassPlanOnce(): MassPlanWithPhases?

    @Transaction
    @Query("SELECT * FROM mass_plans WHERE isTemplate = 1 ORDER BY updatedAtMs DESC")
    fun observeSavedTemplates(): Flow<List<MassPlanWithPhases>>

    @Transaction
    @Query("SELECT * FROM mass_plans WHERE id = :planId LIMIT 1")
    suspend fun getMassPlanByIdOnce(planId: Long): MassPlanWithPhases?

    @Insert
    suspend fun insertMassPlan(plan: MassPlanEntity): Long

    @Query("UPDATE mass_plans SET title = :title, updatedAtMs = :updatedAtMs WHERE id = :planId")
    suspend fun updateMassTitle(planId: Long, title: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE mass_plans SET templateCategory = :category, updatedAtMs = :updatedAtMs WHERE id = :planId")
    suspend fun updateMassPresetId(planId: Long, category: String?, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE mass_plans SET isActive = 0 WHERE isTemplate = 0")
    suspend fun deactivateAllMassPlans()

    @Query("DELETE FROM mass_plans WHERE id = :planId")
    suspend fun deleteMassPlan(planId: Long)

    // --- Phases ---
    @Insert
    suspend fun insertPhase(phase: MassPhaseEntity): Long

    @Insert
    suspend fun insertPhases(phases: List<MassPhaseEntity>): List<Long>

    @Query("UPDATE mass_phases SET isEnabled = :enabled WHERE id = :phaseId")
    suspend fun setPhaseEnabled(phaseId: Long, enabled: Boolean)

    @Query("UPDATE mass_phases SET customLabel = :label WHERE id = :phaseId")
    suspend fun renamePhase(phaseId: Long, label: String)

    @Query("UPDATE mass_phases SET sortOrder = :sortOrder WHERE id = :phaseId")
    suspend fun updatePhaseSortOrder(phaseId: Long, sortOrder: Int)

    @Query("DELETE FROM mass_phases WHERE id = :phaseId")
    suspend fun deletePhase(phaseId: Long)

    @Query("DELETE FROM mass_phases WHERE massPlanId = :planId AND phaseType = 'CUSTOM'")
    suspend fun deleteCustomPhasesInPlan(planId: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM mass_phases WHERE massPlanId = :planId")
    suspend fun getMaxPhaseOrder(planId: Long): Int

    // --- Planned Tracks ---
    @Insert
    suspend fun insertPlannedTrack(track: PlannedTrackEntity): Long

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM planned_tracks WHERE phaseId = :phaseId")
    suspend fun getMaxTrackOrderInPhase(phaseId: Long): Int

    @Query("SELECT * FROM planned_tracks WHERE phaseId = :phaseId ORDER BY sortOrder ASC")
    suspend fun getTracksInPhase(phaseId: Long): List<PlannedTrackEntity>

    @Query("SELECT * FROM planned_tracks WHERE id = :trackId LIMIT 1")
    suspend fun getPlannedTrackById(trackId: Long): PlannedTrackEntity?

    @Query(
        """
        UPDATE planned_tracks
        SET fadeOutMs = :fadeOutMs,
            startOffsetMs = :startOffsetMs,
            transposeSemitones = :transposeSemitones,
            volume = :volume
        WHERE id = :trackId
        """
    )
    suspend fun updatePlannedTrackSettings(
        trackId: Long,
        fadeOutMs: Int,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float
    )

    @Query("UPDATE planned_tracks SET mixerConfigJson = :mixerConfigJson WHERE id = :trackId")
    suspend fun updatePlannedTrackMixerConfig(trackId: Long, mixerConfigJson: String?)

    @Query("UPDATE planned_tracks SET sortOrder = :sortOrder WHERE id = :trackId")
    suspend fun updatePlannedTrackSortOrder(trackId: Long, sortOrder: Int)

    @Query("UPDATE planned_tracks SET playedInSession = :played WHERE id = :trackId")
    suspend fun setTrackPlayedInSession(trackId: Long, played: Boolean)

    @Query(
        """
        UPDATE planned_tracks
        SET playedInSession = 0
        WHERE phaseId IN (SELECT id FROM mass_phases WHERE massPlanId = :planId)
        """
    )
    suspend fun resetSessionPlayedFlags(planId: Long)

    @Query("DELETE FROM planned_tracks WHERE id = :trackId")
    suspend fun deletePlannedTrack(trackId: Long)

    @Query(
        """
        DELETE FROM planned_tracks
        WHERE phaseId IN (SELECT id FROM mass_phases WHERE massPlanId = :planId)
        """
    )
    suspend fun clearAllTracksInPlan(planId: Long)

    @Transaction
    suspend fun saveTrackSettingsAtomic(
        trackId: Long,
        songId: Long,
        fadeOutMs: Int,
        startOffsetMs: Long,
        transposeSemitones: Int,
        volume: Float,
        saveAsSongDefault: Boolean,
        mixerConfigJson: String? = null
    ) {
        updatePlannedTrackSettings(
            trackId = trackId,
            fadeOutMs = fadeOutMs,
            startOffsetMs = startOffsetMs,
            transposeSemitones = transposeSemitones,
            volume = volume
        )
        updatePlannedTrackMixerConfig(trackId = trackId, mixerConfigJson = mixerConfigJson)
        if (saveAsSongDefault) {
            updateSongDefaults(
                songId = songId,
                fadeOutMs = fadeOutMs,
                startOffsetMs = startOffsetMs,
                transposeSemitones = transposeSemitones,
                volume = volume
            )
            updateSongDefaultMixerConfig(songId = songId, mixerConfigJson = mixerConfigJson)
        }
    }
}
