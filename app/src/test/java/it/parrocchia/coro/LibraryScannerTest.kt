package it.parrocchia.coro

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import it.parrocchia.coro.data.db.CoroDao
import it.parrocchia.coro.data.db.CoroDatabase
import it.parrocchia.coro.data.library.LibraryScanner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScannerTest {

    private lateinit var appContext: Context
    private lateinit var db: CoroDatabase
    private lateinit var dao: CoroDao

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(appContext, CoroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.coroDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun mockDocFile(
        name: String?,
        uri: String,
        isDir: Boolean = false,
        isFile: Boolean = !isDir,
        lastModified: Long = 1000L,
        children: Array<DocumentFile> = emptyArray()
    ): DocumentFile {
        val doc = mockk<DocumentFile>(relaxed = true)
        every { doc.name } returns name
        every { doc.uri } returns Uri.parse(uri)
        every { doc.isDirectory } returns isDir
        every { doc.isFile } returns isFile
        every { doc.lastModified() } returns lastModified
        every { doc.listFiles() } returns children
        return doc
    }

    @Test
    fun ensureDemoSongsIndexed_indexesBundledAssetsAndIsIdempotent() = runTest {
        val scanner = LibraryScanner(
            context = appContext,
            dao = dao,
            midiDurationProber = { 123_000L }
        )

        val first = scanner.ensureDemoSongsIndexed(defaultFadeOutMs = 3500)
        assertTrue("Devono esistere i brani demo negli assets", first.isNotEmpty())
        val firstTrack = first.firstOrNull { it.fileName.startsWith("01_") }
        assertTrue(firstTrack != null)
        assertEquals(5_700L, firstTrack!!.defaultStartOffsetMs)
        assertEquals(3500, firstTrack.defaultFadeOutMs)
        assertEquals(123_000L, firstTrack.durationMs)
        assertTrue(firstTrack.isDemo)
        assertTrue(!firstTrack.title.startsWith("01_"))

        // Second call returns existing entries without inserting duplicates
        val second = scanner.ensureDemoSongsIndexed(defaultFadeOutMs = 5000)
        assertEquals(first.size, second.size)
        assertEquals(first.size, dao.countSongs())
    }

    @Test
    fun scanFolderTree_throwsWhenRootIsInaccessible() = runTest {
        val scanner = LibraryScanner(
            context = appContext,
            dao = dao,
            documentTreeResolver = { null }
        )
        var failed = false
        try {
            scanner.scanFolderTree(Uri.parse("content://tree/bad"), 3000)
        } catch (e: IllegalStateException) {
            failed = e.message?.contains("Cartella non accessibile") == true
        }
        assertTrue(failed)
    }

    @Test
    fun scanFolderTree_handlesNestedFoldersUpdatesPreservesDefaultsAndDeletesRemoved() = runTest {
        val resolver = mockk<ContentResolver>()
        val mockCtx = mockk<Context>(relaxed = true)
        every { mockCtx.contentResolver } returns resolver
        every { mockCtx.assets } returns appContext.assets

        val midi1Uri = "content://tree/canto1.mid"
        val audio1Uri = "content://tree/sub/canto2.mp3"
        val midi2Uri = "content://tree/sub/deep/canto3.kar"
        every { resolver.openInputStream(Uri.parse(midi1Uri)) } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        every { resolver.openInputStream(Uri.parse(midi2Uri)) } answers { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }

        val deepDir = mockDocFile(
            name = "Natale",
            uri = "content://tree/sub/deep",
            isDir = true,
            children = arrayOf(
                mockDocFile("___ .kar", midi2Uri, lastModified = 100L),
                mockDocFile(".nascosto.mid", "content://tree/sub/deep/hidden", lastModified = 100L),
                mockDocFile(null, "content://tree/sub/deep/unnamed", lastModified = 100L),
                mockDocFile("testo.pdf", "content://tree/sub/deep/testo.pdf", lastModified = 100L)
            )
        )
        val subDir = mockDocFile(
            name = "TempiForti",
            uri = "content://tree/sub",
            isDir = true,
            children = arrayOf(
                mockDocFile("Canto_Comunione.mp3", audio1Uri, lastModified = 200L),
                deepDir
            )
        )
        var currentRoot = mockDocFile(
            name = "Root",
            uri = "content://tree/root",
            isDir = true,
            children = arrayOf(
                mockDocFile("Gloria_Festivo.mid", midi1Uri, lastModified = 300L),
                subDir
            )
        )

        val scanner = LibraryScanner(
            context = mockCtx,
            dao = dao,
            documentTreeResolver = { currentRoot },
            midiDurationProber = { 90_000L }
        )

        // First scan: adds 3 valid songs
        val s1 = scanner.scanFolderTree(Uri.parse("content://tree/root"), defaultFadeOutMs = 4000)
        assertEquals(3, s1.totalIndexed)
        assertEquals(3, s1.addedCount)
        assertEquals(0, s1.updatedCount)
        assertEquals(0, s1.removedCount)

        val gloria = dao.getSongByUri(midi1Uri)!!
        assertEquals("Gloria Festivo", gloria.title)
        assertEquals(90_000L, gloria.durationMs)
        assertEquals("", gloria.relativeFolder)

        val kar = dao.getSongByUri(midi2Uri)!!
        assertEquals("TempiForti/Natale", kar.relativeFolder)

        // Customize defaults on Gloria, then re-scan with modified timestamp & removed audio file
        dao.updateSongDefaults(gloria.id, fadeOutMs = 7500, startOffsetMs = 4000L, transposeSemitones = 3, volume = 1.3f)

        currentRoot = mockDocFile(
            name = "Root",
            uri = "content://tree/root",
            isDir = true,
            children = arrayOf(
                mockDocFile("Gloria_Festivo.mid", midi1Uri, lastModified = 999L), // modified timestamp
                mockDocFile("___ .kar", midi2Uri, lastModified = 100L) // moved to root (relativeFolder changed)
            )
        )

        val s2 = scanner.scanFolderTree(Uri.parse("content://tree/root"), defaultFadeOutMs = 4000)
        assertEquals(2, s2.totalIndexed)
        assertEquals(0, s2.addedCount)
        assertEquals(2, s2.updatedCount)
        assertEquals(1, s2.removedCount)

        // User defaults preserved across rescan!
        val gloriaAfter = dao.getSongByUri(midi1Uri)!!
        assertEquals(7500, gloriaAfter.defaultFadeOutMs)
        assertEquals(4000L, gloriaAfter.defaultStartOffsetMs)
        assertEquals(3, gloriaAfter.defaultTransposeSemitones)
        assertEquals(1.3f, gloriaAfter.defaultVolume, 1e-5f)

        // Empty folder removes all non-demo songs
        currentRoot = mockDocFile(name = "Root", uri = "content://tree/root", isDir = true, children = emptyArray())
        val s3 = scanner.scanFolderTree(Uri.parse("content://tree/root"), defaultFadeOutMs = 4000)
        assertEquals(0, s3.totalIndexed)
        assertEquals(2, s3.removedCount)
        assertEquals(0, dao.countSongs())
    }

    @Test
    fun scanFolderTree_largeLibraryOver900FilesUsesSafeFallbackForRemovedSongs() = runTest {
        val resolver = mockk<ContentResolver>()
        val mockCtx = mockk<Context>(relaxed = true)
        every { mockCtx.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns null

        // Insert 1 existing song that will disappear
        val orphanUri = "content://tree/orphan.mid"
        dao.insertSong(
            it.parrocchia.coro.data.db.SongEntity(
                uri = orphanUri,
                title = "Orphan",
                fileName = "orphan.mid",
                format = "MIDI",
                isDemo = false
            )
        )

        // Create 905 active files so activeList.size > 900
        val children = Array(905) { idx ->
            mockDocFile("song_$idx.mid", "content://tree/song_$idx.mid", lastModified = 10L)
        }
        val root = mockDocFile("Root", "content://tree/root", isDir = true, children = children)

        val scanner = LibraryScanner(
            context = mockCtx,
            dao = dao,
            documentTreeResolver = { root },
            midiDurationProber = { 60_000L }
        )

        val summary = scanner.scanFolderTree(Uri.parse("content://tree/root"), 3000)
        assertEquals(905, summary.totalIndexed)
        assertEquals(905, summary.addedCount)
        assertEquals(1, summary.removedCount)
        assertEquals("__REMOVED__", dao.getSongByUri(orphanUri)?.relativeFolder)
    }
}
