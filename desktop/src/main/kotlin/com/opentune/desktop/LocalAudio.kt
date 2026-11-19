package com.opentune.desktop

import com.opentune.data.model.Song
import java.awt.FileDialog
import java.io.File
import java.net.URI

internal object LocalAudio {
    private const val prefix = "local:"
    private val extensions = setOf("aac", "aif", "aiff", "alac", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "webm", "wma")

    fun play(files: List<File>) {
        val songs = files.mapNotNull(::songOf).distinctBy(Song::videoId)
        Player.playAll(songs, 0)
    }

    fun chooseFiles() {
        val window = DesktopWindow.window ?: return
        val dialog = FileDialog(window, "Open audio files", FileDialog.LOAD)
        try {
            dialog.isMultipleMode = true
            dialog.setFilenameFilter { _, name -> supported(File(name)) }
            dialog.isVisible = true
            play(dialog.files.toList())
        } finally {
            dialog.dispose()
        }
    }

    private fun songOf(file: File): Song? {
        if (!supported(file) || !file.isFile || !file.canRead()) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val uri = canonical.toURI().toASCIIString()
        return Song("$prefix$uri", file.nameWithoutExtension, "", thumbnailUrl = null, localUri = uri)
    }

    /** Resolve persisted song ids back to readable files, including paths with spaces and Unicode. */
    fun addressOf(id: String): String? {
        if (!id.startsWith("${prefix}file:")) return null
        val file = runCatching { File(URI(id.removePrefix(prefix))) }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.canRead() }?.absolutePath
    }

    private fun supported(file: File) = file.extension.lowercase() in extensions
}
