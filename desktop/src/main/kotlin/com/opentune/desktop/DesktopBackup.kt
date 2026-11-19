package com.opentune.desktop

import com.opentune.data.history.PlayRecord
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SettingsState
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object DesktopBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class BackupSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val albumName: String? = null,
        val explicit: Boolean? = null,
    ) {
        fun song() = com.opentune.data.model.Song(videoId, title, artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit)
    }

    @Serializable
    data class Playlist(val id: String, val name: String, val songs: List<BackupSong> = emptyList())

    @Serializable
    data class Collections(val liked: List<BackupSong> = emptyList(), val playlists: List<Playlist> = emptyList())

    @Serializable
    data class Document(
        val app: String,
        val version: Int,
        val settings: JsonElement? = null,
        val history: List<PlayRecord>? = null,
        val library: Collections? = null,
    )

    private fun com.opentune.data.model.Song.ref() = BackupSong(videoId, title, artist, thumbnailUrl, durationText, albumName, isExplicit)

    fun export(file: File) {
        val data = Document(
            "OpenTune", 1, AppSettings.exportJson(), Library.history(Int.MAX_VALUE),
            Collections(
                Library.liked.value.map { it.ref() },
                Library.playlists.value.map { list -> Playlist(list.id, list.name, list.songs.map { it.toSong().ref() }) },
            ),
        )
        val destination = file.absoluteFile.toPath()
        val staged = Files.createTempFile(destination.parent, ".opentune-backup-", ".json")
        try {
            Files.writeString(staged, json.encodeToString(data))
            try {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    fun restore(file: File) {
        val document = json.decodeFromString<Document>(file.readText())
        require(document.app == "OpenTune" && document.version == 1) { "Choose an OpenTune version 1 backup." }
        document.settings?.let { AppSettings.json.decodeFromJsonElement(SettingsState.serializer(), it) }
        Library.restoreBackup(document.library, document.history.orEmpty())
        document.settings?.let(AppSettings::importJson)
    }
}
