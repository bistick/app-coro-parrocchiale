package it.parrocchia.coro

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import it.parrocchia.coro.data.db.CoroDao
import it.parrocchia.coro.data.db.CoroDatabase
import it.parrocchia.coro.data.db.MassPhaseEntity
import it.parrocchia.coro.data.db.MassPlanEntity
import it.parrocchia.coro.data.db.PlannedTrackEntity
import it.parrocchia.coro.data.db.SongEntity
import it.parrocchia.coro.domain.model.AudioFormat
import it.parrocchia.coro.domain.model.StandardPhaseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoroDatabaseAndDaoTest {

    private lateinit var context: Context
    private lateinit var db: CoroDatabase
    private lateinit var dao: CoroDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CoroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.coroDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getInstance_returnsSameSingleton() {
        val db1 = CoroDatabase.getInstance(context)
        val db2 = CoroDatabase.getInstance(context)
        assertSame(db1, db2)
    }

    @Test
    fun songsDao_insertQueryUpdateDefaultsAndDeleteMissing() = runTest {
        val demoId = dao.insertSong(
            SongEntity(
                uri = "asset:///demo/01.mid",
                title = "Alpha Demo",
                fileName = "01.mid",
                relativeFolder = "Brani di prova",
                format = AudioFormat.MIDI.name,
                isDemo = true
            )
        )
        val user1Id = dao.insertSong(
            SongEntity(
                uri = "content://user/b.mp3",
                title = "Beta",
                fileName = "b.mp3",
                relativeFolder = "Ingresso",
                format = AudioFormat.AUDIO.name,
                isDemo = false
            )
        )
        val user2Id = dao.insertSong(
            SongEntity(
                uri = "content://user/a.mid",
                title = "acqua viva",
                fileName = "a.mid",
                relativeFolder = "Ingresso",
                format = AudioFormat.MIDI.name,
                isDemo = false
            )
        )
        // Duplicate URI ignored
        assertEquals(-1L, dao.insertSong(
            SongEntity(
                uri = "content://user/a.mid",
                title = "Duplicate",
                fileName = "a.mid",
                format = AudioFormat.MIDI.name
            )
        ))

        assertEquals(3, dao.countSongs())
        val observed = dao.observeAllSongs().first()
        // Non-demo first, case-insensitive title ascending, then demo
        assertEquals(listOf(user2Id, user1Id, demoId), observed.map { it.id })
        assertEquals(3, dao.getAllSongsOnce().size)
        assertEquals("Beta", dao.getSongById(user1Id)?.title)
        assertEquals(user2Id, dao.getSongByUri("content://user/a.mid")?.id)

        dao.updateSong(dao.getSongById(user1Id)!!.copy(title = "Beta Updated"))
        assertEquals("Beta Updated", dao.getSongById(user1Id)?.title)

        dao.updateSongDefaults(user1Id, fadeOutMs = 5500, startOffsetMs = 12_000L, transposeSemitones = -2, volume = 1.3f)
        val updated = dao.getSongById(user1Id)!!
        assertEquals(5500, updated.defaultFadeOutMs)
        assertEquals(12_000L, updated.defaultStartOffsetMs)
        assertEquals(-2, updated.defaultTransposeSemitones)
        assertEquals(1.3f, updated.defaultVolume, 1e-5f)

        dao.deleteMissingNonDemoSongs(listOf("content://user/a.mid"))
        assertNull(dao.getSongById(user1Id))
        assertNotNull(dao.getSongById(user2Id))
        assertNotNull(dao.getSongById(demoId))

        dao.deleteAllNonDemoSongs()
        assertEquals(1, dao.countSongs())
        assertNotNull(dao.getSongById(demoId))
    }

    @Test
    fun massPlansPhasesAndTracks_crudOrderingTransactionsAndCascadeDelete() = runTest {
        val songId = dao.insertSong(
            SongEntity(
                uri = "content://song/gloria.mid",
                title = "Gloria",
                fileName = "gloria.mid",
                format = AudioFormat.MIDI.name
            )
        )

        val planId = dao.insertMassPlan(
            MassPlanEntity(
                title = "Messa Domenicale",
                dateEpochDay = 20_000L,
                isTemplate = false,
                templateCategory = "domenicale",
                isActive = true
            )
        )
        val templateId = dao.insertMassPlan(
            MassPlanEntity(
                title = "Mio Preset",
                dateEpochDay = 20_000L,
                isTemplate = true,
                templateCategory = "solenne",
                isActive = false
            )
        )

        assertEquals(planId, dao.getActiveMassPlanOnce()?.plan?.id)
        assertEquals(planId, dao.observeActiveMassPlan().first()?.plan?.id)
        assertEquals(listOf(templateId), dao.observeSavedTemplates().first().map { it.plan.id })
        assertEquals("Mio Preset", dao.getMassPlanByIdOnce(templateId)?.plan?.title)

        dao.updateMassTitle(planId, "Messa Solenne")
        dao.updateMassPresetId(planId, "solenne")
        assertEquals("Messa Solenne", dao.getActiveMassPlanOnce()?.plan?.title)
        assertEquals("solenne", dao.getActiveMassPlanOnce()?.plan?.templateCategory)

        val stdPhaseId = dao.insertPhase(
            MassPhaseEntity(
                massPlanId = planId,
                phaseType = StandardPhaseType.INGRESSO.name,
                sortOrder = 10,
                isEnabled = true
            )
        )
        val customPhaseIds = dao.insertPhases(
            listOf(
                MassPhaseEntity(
                    massPlanId = planId,
                    phaseType = StandardPhaseType.CUSTOM.name,
                    customLabel = "Kalenda",
                    sortOrder = 5,
                    isEnabled = true
                )
            )
        )
        val customPhaseId = customPhaseIds.first()
        assertEquals(10, dao.getMaxPhaseOrder(planId))

        dao.renamePhase(customPhaseId, "Annuncio Kalenda")
        dao.updatePhaseSortOrder(customPhaseId, 15)
        dao.setPhaseEnabled(customPhaseId, false)
        assertEquals(15, dao.getMaxPhaseOrder(planId))

        val trackId1 = dao.insertPlannedTrack(
            PlannedTrackEntity(phaseId = stdPhaseId, songId = songId, sortOrder = 0)
        )
        val trackId2 = dao.insertPlannedTrack(
            PlannedTrackEntity(phaseId = stdPhaseId, songId = songId, sortOrder = 1)
        )
        assertEquals(1, dao.getMaxTrackOrderInPhase(stdPhaseId))
        assertEquals(listOf(trackId1, trackId2), dao.getTracksInPhase(stdPhaseId).map { it.id })

        dao.updatePlannedTrackSortOrder(trackId2, 0)
        dao.updatePlannedTrackSortOrder(trackId1, 1)
        assertEquals(listOf(trackId2, trackId1), dao.getTracksInPhase(stdPhaseId).map { it.id })

        // Atomic save with saveAsSongDefault = true (including mixerConfigJson)
        val soloistJson = """{"unmuteFaderIndices":[0]}"""
        dao.saveTrackSettingsAtomic(
            trackId = trackId1,
            songId = songId,
            fadeOutMs = 6000,
            startOffsetMs = 3000L,
            transposeSemitones = 3,
            volume = 1.25f,
            saveAsSongDefault = true,
            mixerConfigJson = soloistJson
        )
        assertEquals(6000, dao.getPlannedTrackById(trackId1)?.fadeOutMs)
        assertEquals(soloistJson, dao.getPlannedTrackById(trackId1)?.mixerConfigJson)
        assertEquals(6000, dao.getSongById(songId)?.defaultFadeOutMs)
        assertEquals(soloistJson, dao.getSongById(songId)?.defaultMixerConfigJson)
        assertEquals(soloistJson, dao.getSongById(songId)?.defaultSettings?.mixerConfigJson)

        // Atomic save with saveAsSongDefault = false
        val extraMicJson = """{"unmuteFaderIndices":[0,1]}"""
        dao.saveTrackSettingsAtomic(
            trackId = trackId1,
            songId = songId,
            fadeOutMs = 2000,
            startOffsetMs = 1000L,
            transposeSemitones = -1,
            volume = 0.8f,
            saveAsSongDefault = false,
            mixerConfigJson = extraMicJson
        )
        assertEquals(2000, dao.getPlannedTrackById(trackId1)?.fadeOutMs)
        assertEquals(extraMicJson, dao.getPlannedTrackById(trackId1)?.mixerConfigJson)
        assertEquals(6000, dao.getSongById(songId)?.defaultFadeOutMs)
        assertEquals(soloistJson, dao.getSongById(songId)?.defaultMixerConfigJson)

        dao.setTrackPlayedInSession(trackId1, true)
        assertTrue(dao.getPlannedTrackById(trackId1)!!.playedInSession)
        dao.resetSessionPlayedFlags(planId)
        assertFalse(dao.getPlannedTrackById(trackId1)!!.playedInSession)

        dao.deleteCustomPhasesInPlan(planId)
        assertEquals(1, dao.getActiveMassPlanOnce()?.phases?.size)

        dao.deletePlannedTrack(trackId2)
        assertEquals(1, dao.getTracksInPhase(stdPhaseId).size)

        dao.clearAllTracksInPlan(planId)
        assertEquals(0, dao.getTracksInPhase(stdPhaseId).size)

        dao.deactivateAllMassPlans()
        assertNull(dao.getActiveMassPlanOnce())

        dao.deleteMassPlan(templateId)
        assertNull(dao.getMassPlanByIdOnce(templateId))
    }

    @Test
    fun migration1To2_addsDefaultMixerConfigJsonColumn() {
        val dbFile = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("test_migration_1_2.db")
        dbFile.delete()
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                uri TEXT NOT NULL,
                title TEXT NOT NULL,
                fileName TEXT NOT NULL,
                relativeFolder TEXT NOT NULL,
                format TEXT NOT NULL,
                durationMs INTEGER NOT NULL,
                defaultFadeOutMs INTEGER NOT NULL,
                defaultStartOffsetMs INTEGER NOT NULL,
                defaultTransposeSemitones INTEGER NOT NULL,
                defaultVolume REAL NOT NULL,
                isDemo INTEGER NOT NULL,
                lastModifiedMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        val supportDb = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteDatabase>()
        io.mockk.every { supportDb.execSQL(any()) } answers {
            sqlite.execSQL(firstArg<String>())
        }
        CoroDatabase.MIGRATION_1_2.migrate(supportDb)

        sqlite.rawQuery("PRAGMA table_info(songs)", null).use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(1))
            }
            assertTrue("defaultMixerConfigJson" in columns)
        }
        sqlite.close()
        dbFile.delete()
    }
}
