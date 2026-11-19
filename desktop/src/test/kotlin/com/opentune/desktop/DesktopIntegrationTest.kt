package com.opentune.desktop

import com.opentune.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopIntegrationTest {
    @Test
    fun itRestoresAnAndroidBackupWithoutDuplicatingExistingPlays() {
        val folder = kotlin.io.path.createTempDirectory().toFile()
        val legacy = folder.resolve("desktop.json")
        Library.useFile(legacy)
        try {
            Library.setLiked(Song("abcdefghijk", "Existing", "Artist", null), true)
            val file = folder.resolve("android-backup.json")
            file.writeText("""{
                "app":"OpenTune", "version":1,
                "history":[{"videoId":"01234567890","title":"Track","artist":"Artist","playedAt":5000,"listenedMs":3000}],
                "library":{"liked":[{"videoId":"01234567890","title":"Track","artist":"Artist","explicit":true}],
                "playlists":[{"id":"from-phone","name":"From phone","songs":[{"videoId":"01234567890","title":"Track","artist":"Artist"}]}]}
            }""".trimIndent())
            repeat(2) { DesktopBackup.restore(file) }
            assertEquals(setOf("abcdefghijk", "01234567890"), Library.liked.value.map { it.videoId }.toSet())
            assertEquals(listOf("01234567890"), Library.history().map { it.videoId })
            assertEquals(3000L, Library.history().single().listenedMs)
            assertEquals(true, Library.liked.value.single { it.videoId == "01234567890" }.isExplicit)
            assertEquals(listOf("01234567890"), Library.playlists.value.single().songs.map { it.id })
        } finally {
            Library.useFile(java.io.File(System.getProperty("user.home"), ".opentune/desktop.json"))
            folder.deleteRecursively()
        }
    }

    @Test
    fun itParsesTrackAndPlaylistLinksWithoutAcceptingImpostorHosts() {
        assertEquals(DesktopLinks.Link.Track("abcdefghijk"), DesktopLinks.parse("https://music.youtube.com/watch?v=abcdefghijk"))
        assertEquals(DesktopLinks.Link.Playlist("VLPLabcdefghijk"), DesktopLinks.parse("https://youtube.com/playlist?list=PLabcdefghijk"))
        assertNull(DesktopLinks.parse("https://example.org/music.youtube.com/watch?v=abcdefghijk"))
        assertNull(DesktopLinks.parse("https://music.youtube.com.evil.example/watch?v=abcdefghijk"))
    }

    @Test
    fun itOnlyOffersAStableUniversalInstallerWithAChecksum() {
        fun release(version: String, prerelease: Boolean = false, digest: String = "a".repeat(64)): JSONObject {
            val tag = "macos-v$version"
            val filename = "OpenTune-v$version-macos-universal.dmg"
            return JSONObject().put("tag_name", tag).put("prerelease", prerelease).put("assets", JSONArray().put(
                JSONObject().put("name", filename)
                    .put("browser_download_url", "https://github.com/747elias/Opentune/releases/download/$tag/$filename")
                    .put("digest", "sha256:$digest"),
            ))
        }
        val releases = JSONArray().put(release("2.2", prerelease = true)).put(release("1.10"))
            .put(release("2.1", digest = "invalid")).put(release("1.9"))
        assertEquals("1.10", MacUpdates.findInstaller(releases, "1.9.0")?.version)
        assertNull(MacUpdates.findInstaller(releases, "2.0"))
    }
}
