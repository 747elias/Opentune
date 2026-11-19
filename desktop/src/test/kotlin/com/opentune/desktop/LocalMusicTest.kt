package com.opentune.desktop

import com.opentune.data.isYouTubeId
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicTest {
    @Test fun localFoldersKeepOnePlayableReferencePerFile() = runBlocking {
        val folder = Files.createTempDirectory("opentune-local").toFile()
        val nested = folder.resolve("My albums").apply { mkdirs() }
        val file = nested.resolve("An # Étude.flac")
        javaClass.getResourceAsStream("/flac/sine-24-96k.flac")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        nested.resolve("broken.mp3").writeText("this file has no audio")

        val scan = DesktopLocalMusic.scan(listOf(folder, file))
        val track = scan.songs.single()
        assertEquals(1, scan.skipped)
        assertFalse(isYouTubeId(track.videoId))
        assertEquals(file.canonicalFile, DesktopLocalMusic.fileOf(track))
        assertEquals("FLAC", DesktopLocalMusic.qualityOf(file).detail)

        val dbLocation = folder.resolve("desktop.json")
        Library.useFile(dbLocation)
        Library.addLocalTracks(listOf(track))
        Library.setLiked(track, true)
        val playlist = Library.createPlaylist("Local playlist")
        Library.addToPlaylist(playlist, track)
        Library.useFile(dbLocation)

        assertEquals(file.canonicalFile, DesktopLocalMusic.fileOf(Library.localTracks.value.single()))
        assertEquals(file.canonicalFile, DesktopLocalMusic.fileOf(Library.playlists.value.single().songs.single().toSong()))
        Library.removeLocalTrack(track.videoId)
        assertTrue(Library.localTracks.value.isEmpty())
        assertEquals(file.canonicalFile, DesktopLocalMusic.fileOf(Library.liked.value.single()))
        assertTrue(file.isFile)
    }
}
