package it.parrocchia.coro

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import it.parrocchia.coro.data.library.SoundFontManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundFontManagerTest {

    private lateinit var appContext: Context
    private lateinit var manager: SoundFontManager

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        File(appContext.filesDir, "soundfonts").deleteRecursively()
        manager = SoundFontManager(appContext)
    }

    private fun validSoundFontBytes(size: Int = 2048): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 'R'.code.toByte()
        bytes[1] = 'I'.code.toByte()
        bytes[2] = 'F'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[8] = 's'.code.toByte()
        bytes[9] = 'f'.code.toByte()
        bytes[10] = 'b'.code.toByte()
        bytes[11] = 'k'.code.toByte()
        return bytes
    }

    @Test
    fun ensureBundledSoundFontPath_extractsAndCachesAsset() = runTest {
        val path1 = manager.ensureBundledSoundFontPath()
        val file = File(path1)
        assertTrue(file.exists())
        assertTrue(file.length() > 1024L)
        assertTrue(path1.endsWith(SoundFontManager.BUNDLED_SF2_FILENAME))

        // Second call hits cache branch
        val path2 = manager.ensureBundledSoundFontPath()
        assertEquals(path1, path2)

        // Corrupt/tiny file gets re-extracted
        file.writeBytes(ByteArray(10))
        val path3 = manager.ensureBundledSoundFontPath()
        assertTrue(File(path3).length() > 1024L)
    }

    @Test
    fun resolveActiveSoundFontPath_usesValidCustomOrFallsBackToBundled() = runTest {
        val bundled = manager.ensureBundledSoundFontPath()

        assertEquals(bundled, manager.resolveActiveSoundFontPath(null))
        assertEquals(bundled, manager.resolveActiveSoundFontPath("   "))
        assertEquals(bundled, manager.resolveActiveSoundFontPath("/nonexistent/file.sf2"))

        val tiny = File(appContext.filesDir, "tiny.sf2").apply { writeBytes(ByteArray(100)) }
        assertEquals(bundled, manager.resolveActiveSoundFontPath(tiny.absolutePath))

        val validCustom = File(appContext.filesDir, "custom.sf2").apply { writeBytes(validSoundFontBytes()) }
        assertEquals(validCustom.absolutePath, manager.resolveActiveSoundFontPath(validCustom.absolutePath))
    }

    @Test
    fun importCustomSoundFont_supportsSf2Sf3RejectsInvalidAndClears() = runTest {
        val resolver = mockk<ContentResolver>()
        val mockCtx = mockk<Context>(relaxed = true)
        every { mockCtx.filesDir } returns appContext.filesDir
        every { mockCtx.assets } returns appContext.assets
        every { mockCtx.contentResolver } returns resolver

        val sut = SoundFontManager(mockCtx)
        val sf2Uri = Uri.parse("content://docs/FluidR3.sf2")
        val sf3Uri = Uri.parse("content://docs/MuseScore.sf3")
        val badUri = Uri.parse("content://docs/not_a_sf.bin")
        val nullStreamUri = Uri.parse("content://docs/missing.sf2")

        // Valid SF2
        val sf2Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf("FluidR3.sf2"))
        }
        every { resolver.query(sf2Uri, any(), any(), any(), any()) } returns sf2Cursor
        every { resolver.openInputStream(sf2Uri) } returns ByteArrayInputStream(validSoundFontBytes())

        val resSf2 = sut.importCustomSoundFont(sf2Uri)
        assertTrue(resSf2.isSuccess)
        val (sf2Path, sf2Name) = resSf2.getOrThrow()
        assertEquals("FluidR3.sf2", sf2Name)
        assertTrue(sf2Path.endsWith("custom_active.sf2"))
        assertTrue(File(sf2Path).exists())

        // Valid SF3 replaces SF2
        val sf3Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf("MuseScore.sf3"))
        }
        every { resolver.query(sf3Uri, any(), any(), any(), any()) } returns sf3Cursor
        every { resolver.openInputStream(sf3Uri) } returns ByteArrayInputStream(validSoundFontBytes())

        val resSf3 = sut.importCustomSoundFont(sf3Uri)
        assertTrue(resSf3.isSuccess)
        val (sf3Path, sf3Name) = resSf3.getOrThrow()
        assertEquals("MuseScore.sf3", sf3Name)
        assertTrue(sf3Path.endsWith("custom_active.sf3"))
        assertFalse(File(sf2Path).exists())
        assertTrue(File(sf3Path).exists())

        // Clear custom soundfonts
        sut.clearCustomSoundFont()
        assertFalse(File(sf3Path).exists())

        // Invalid header rejected
        every { resolver.query(badUri, any(), any(), any(), any()) } returns null
        every { resolver.openInputStream(badUri) } returns ByteArrayInputStream("NOT_A_SOUNDFONT".toByteArray())
        val resBad = sut.importCustomSoundFont(badUri)
        assertTrue(resBad.isFailure)

        // Null stream rejected
        every { resolver.query(nullStreamUri, any(), any(), any(), any()) } returns MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        every { resolver.openInputStream(nullStreamUri) } returns null
        val resMissing = sut.importCustomSoundFont(nullStreamUri)
        assertTrue(resMissing.isFailure)
    }
}
