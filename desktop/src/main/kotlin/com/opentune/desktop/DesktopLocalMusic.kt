package com.opentune.desktop

import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber

/** Audio selected by the listener, referenced by its file URI in the library. */
internal object DesktopLocalMusic {
    private val formats = mapOf(
        "aac" to "AAC", "aif" to "AIFF", "aiff" to "AIFF", "alac" to "ALAC", "caf" to "CAF",
        "flac" to "FLAC", "m4a" to "M4A", "m4b" to "M4B", "mka" to "Matroska", "mp3" to "MP3", "mp4" to "MP4",
        "oga" to "Ogg", "ogg" to "Ogg", "opus" to "Opus", "wav" to "WAV", "wave" to "WAV",
        "webm" to "WebM", "wma" to "WMA", "wv" to "WavPack",
    )

    data class Scan(val songs: List<Song>, val skipped: Int)

    fun fileOf(song: Song): File? {
        if (!LocalMusic.isLocal(song.videoId)) return null
        val address = song.videoId.removePrefix(LocalMusic.PREFIX)
        return runCatching { File(URI(address)) }.getOrNull()?.takeIf { it.isFile && it.canRead() }
    }

    fun qualityOf(file: File) = Player.Quality("Local file", formats[file.extension.lowercase()] ?: "Audio")

    suspend fun chooseFiles(folders: Boolean): List<File> = desktopFiles(
        title = if (folders) "Add music folder" else "Add audio files",
        multiple = !folders,
        directories = folders,
        extensions = if (folders) emptySet() else formats.keys,
    )

    suspend fun scan(selected: List<File>): Scan = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val found = mutableListOf<Song>()
        val seen = HashSet<File>()
        var skipped = 0

        fun add(file: File) {
            context.ensureActive()
            if (file.extension.lowercase() !in formats) return
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical == null || !canonical.isFile || !canonical.canRead()) {
                skipped++
                return
            }
            if (!seen.add(canonical)) return
            val song = read(canonical)
            if (song == null) skipped++ else found += song
        }

        for (selection in selected) {
            if (!selection.isDirectory) {
                add(selection)
                continue
            }
            val root = runCatching { selection.toPath().toRealPath() }.getOrNull()
            if (root == null) {
                skipped++
                continue
            }
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) add(file.toFile())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        skipped++
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: IOException) {
                skipped++
            }
        }
        Scan(found, skipped)
    }

    private fun read(file: File): Song? = runCatching {
        if (!Ffmpeg.available) return null
        FFmpegFrameGrabber(file).use { audio ->
            audio.setOption("protocol_whitelist", "file")
            audio.start()
            if (!audio.hasAudio()) return null
            val tags = (audio.metadata + audio.audioMetadata).mapKeys { it.key.lowercase() }
            fun tag(name: String) = tags[name]?.trim()?.takeIf(String::isNotEmpty)
            val uri = file.toURI().toASCIIString()
            Song(
                videoId = LocalMusic.PREFIX + uri,
                title = tag("title") ?: file.nameWithoutExtension,
                artist = tag("artist") ?: tag("album_artist") ?: "Unknown artist",
                thumbnailUrl = null,
                durationText = audio.lengthInTime.takeIf { it > 0 }?.let { Time.format(it / 1_000) },
                albumName = tag("album"),
                localUri = uri,
                localPath = file.absolutePath,
            )
        }
    }.getOrNull()
}
