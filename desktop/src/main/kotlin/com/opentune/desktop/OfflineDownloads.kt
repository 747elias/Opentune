package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.SavedFile
import com.opentune.data.download.RangedFetch
import com.opentune.data.download.SMART_SONG_LIMIT
import com.opentune.data.download.SmartDownloadDeferred
import com.opentune.data.download.selectSmartDownloads
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.bytedeco.javacv.FFmpegFrameGrabber

/** The on-disk library and download queue for the desktop. */
internal object OfflineDownloads {
    @Serializable
    enum class State { QUEUED, DOWNLOADING, DONE, FAILED }

    @Serializable
    data class Entry(
        val song: Song,
        val workId: String,
        val quality: AudioQuality,
        val state: State = State.QUEUED,
        val progress: Float = 0f,
        val kind: Streams.Kind? = null,
        val bitrate: Int = 0,
        val bytes: Long = 0L,
        val loudnessDb: Double? = null,
        val error: String? = null,
        val smartDownload: Boolean = false,
        val addedAt: Long = 0L,
    ) {
        val detail: String? get() = kind?.let { if (bitrate > 0) "${it.label} $bitrate kbps" else it.label }
    }

    data class Audio(val file: File, val format: String, val detail: String, val lossless: Boolean, val loudnessDb: Double?)

    private val Streams.Kind.label: String get() = if (this == Streams.Kind.OPUS) "Opus" else "AAC"
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val uuid = Regex("[a-f0-9-]{36}")
    private val root = File(appContext.filesDir, "downloads")
    private val index = SavedFile(File(root, "index.json"), "Desktop downloads")
    private val json = Json { ignoreUnknownKeys = true }
    // A second process can play existing copies, while the first owns the queue and index.
    private val indexLock = runCatching {
        Files.createDirectories(root.toPath())
        val channel = FileChannel.open(File(root, "queue.lock").toPath(), CREATE, WRITE)
        try {
            channel.tryLock().also { if (it == null) channel.close() }
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val jobs = mutableMapOf<String, Job>()
    private val _entries = MutableStateFlow(
        readIndex().filter { (id, entry) ->
            id == entry.song.videoId && id.matches(videoId) && entry.workId.matches(uuid)
        }.mapValues { (_, entry) ->
            when {
                entry.state == State.DOWNLOADING -> entry.copy(state = State.QUEUED, progress = 0f)
                entry.state == State.DONE && audioFile(entry) == null ->
                    entry.copy(state = State.FAILED, progress = 0f, bytes = 0L, error = "Audio file is missing. Try downloading it again.")
                else -> entry
            }
        },
    )
    val entries = _entries.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    @Synchronized
    fun resumePending() {
        if (indexLock.getOrNull() == null) return
        _entries.value.values.filter { !it.smartDownload && it.state == State.QUEUED && it.workId !in jobs }.forEach(::start)
    }

    fun supports(song: Song): Boolean = song.videoId.matches(videoId) && song.localUri == null

    fun entry(videoId: String) = entries.map { it[videoId] }.distinctUntilChanged()

    fun enqueue(song: Song) = enqueueAll(listOf(song))

    @Synchronized
    fun enqueueAll(songs: List<Song>) {
        val current = _entries.value
        val changed = songs.filter(::supports).distinctBy(Song::videoId).mapNotNull { song ->
            val previous = current[song.videoId]
            when {
                needsDownload(previous) -> song.videoId to queued(song, smart = false)
                previous?.smartDownload == true -> song.videoId to previous.copy(smartDownload = false)
                else -> null
            }
        }.toMap()
        if (changed.isEmpty() || !save(current + changed)) return
        changed.forEach { (id, entry) ->
            current[id]?.takeIf { it.workId != entry.workId }?.let(::dispose)
            if (entry.state == State.QUEUED && entry.workId !in jobs) start(entry)
        }
    }

    /** Apply the limit immediately, even when new downloads must wait for power or network. */
    @Synchronized
    fun trimSmart(enabled: Boolean, limit: Int) {
        val smart = _entries.value.values.filter(Entry::smartDownload)
        val discard = if (enabled) smart.sortedByDescending(Entry::addedAt).drop(limit.coerceIn(SMART_SONG_LIMIT))
            else smart.filter { it.state != State.DONE }
        removeAll(discard.mapTo(mutableSetOf()) { it.song.videoId })
    }

    @Synchronized
    fun syncSmart(candidates: List<Song>, limit: Int) {
        if (indexLock.getOrNull() == null || !DesktopSmartDownloads.allowed()) return
        val current = _entries.value
        val manual = current.filterValues { !it.smartDownload }.keys
        val desired = selectSmartDownloads(candidates.filter(::supports), manual, limit).associateBy(Song::videoId)
        val discard = current.filter { (id, entry) -> entry.smartDownload && id !in desired }
        val pending = desired.filterKeys { needsDownload(current[it]) }.mapValues { (_, song) -> queued(song, smart = true) }
        if (discard.isNotEmpty() || pending.isNotEmpty()) {
            if (!save(current - discard.keys + pending)) return
            (discard.values + pending.keys.mapNotNull(current::get)).forEach(::dispose)
        }
        resumeSmart()
    }

    @Synchronized
    fun resumeSmart() {
        if (indexLock.getOrNull() == null || !DesktopSmartDownloads.allowed()) return
        val smart = _entries.value.values.filter(Entry::smartDownload)
        if (smart.any { it.workId in jobs }) return
        smart.firstOrNull { it.state == State.QUEUED }?.let(::start)
    }

    @Synchronized
    fun retry(videoId: String) {
        val entry = _entries.value[videoId]?.takeIf { it.state == State.FAILED } ?: return
        if (!entry.smartDownload) {
            enqueue(entry.song)
            return
        }
        if (!AppSettings.library.value.smartDownloads) return
        val retry = queued(entry.song, smart = true)
        if (save(_entries.value + (videoId to retry))) {
            dispose(entry)
            resumeSmart()
        }
    }

    @Synchronized
    fun cancel(videoId: String) {
        val state = _entries.value[videoId]?.state
        if (state == State.QUEUED || state == State.DOWNLOADING) remove(videoId)
    }

    @Synchronized
    fun remove(videoId: String) = removeAll(setOf(videoId))

    @Synchronized
    fun cancelAll(videoIds: Set<String>) {
        removeAll(videoIds.filterTo(mutableSetOf()) { id ->
            _entries.value[id]?.state in setOf(State.QUEUED, State.DOWNLOADING)
        })
    }

    @Synchronized
    fun removeAll(videoIds: Set<String>) {
        val current = _entries.value
        val removed = videoIds.mapNotNull(current::get)
        if (removed.isEmpty() || !save(current - videoIds)) return
        removed.forEach(::dispose)
    }

    fun audio(videoId: String): Audio? {
        val entry = _entries.value[videoId]?.takeIf { it.state == State.DONE } ?: return null
        val file = audioFile(entry) ?: return null
        val format = entry.kind?.label ?: return null
        return Audio(file, format, entry.detail ?: format, false, entry.loudnessDb)
    }

    fun playableSong(entry: Entry): Song = entry.song.copy(
        localUri = audioFile(entry)?.toURI()?.toASCIIString(),
        thumbnailUrl = File(folder(entry), "cover").takeIf { it.isFile }?.toURI()?.toString() ?: entry.song.thumbnailUrl,
    )

    fun lyricsFileFor(videoId: String): File? = _entries.value[videoId]?.takeIf { it.state == State.DONE }
        ?.let { File(folder(it), "lyrics.json") }

    private fun folder(entry: Entry) = File(root, "${entry.song.videoId}.${entry.workId}")

    private fun audioFile(entry: Entry): File? = entry.kind?.let { File(folder(entry), "audio.${it.extension}") }
        ?.takeIf { it.isFile && entry.bytes > 0L && it.length() == entry.bytes }

    private fun needsDownload(entry: Entry?): Boolean =
        entry == null || entry.state == State.FAILED || (entry.state == State.DONE && audioFile(entry) == null)

    private fun queued(song: Song, smart: Boolean): Entry {
        val quality = AppSettings.library.value.downloadQuality.takeUnless { it == AudioQuality.MAX } ?: AudioQuality.HIGH
        return Entry(song, UUID.randomUUID().toString(), quality, smartDownload = smart, addedAt = System.currentTimeMillis())
    }

    private fun dispose(entry: Entry) {
        jobs.remove(entry.workId)?.cancel()
        folder(entry).deleteRecursively()
    }

    private fun start(entry: Entry) {
        val job = scope.launch(start = CoroutineStart.LAZY) { download(entry) }
        jobs[entry.workId] = job
        job.start()
    }

    private suspend fun download(entry: Entry) {
        val directory = folder(entry)
        val part = File(directory, "audio.part")
        var completed = false
        var deferred = false
        try {
            val durationMs = slots.withPermit {
                requireSmartConditions(entry)
                if (!update(entry) { it.copy(state = State.DOWNLOADING) }) return
                Files.createDirectories(directory.toPath())
                val (kind, stream, durationMs) = fetch(entry, part)
                requireSmartConditions(entry)
                val file = File(directory, "audio.${kind.extension}")
                part.moveTo(file)
                saveCover(entry, directory)
                completed = update(entry) {
                    it.copy(state = State.DONE, progress = 1f, kind = kind, bitrate = stream.kbps,
                        bytes = file.length(), loudnessDb = stream.loudnessDb)
                }
                if (!completed) return
                durationMs
            }
            val song = entry.song
            LyricsRepository.saveOffline(song.videoId, song.title, song.artist, durationMs)
        } catch (_: SmartDownloadDeferred) {
            deferred = true
            update(entry) { it.copy(state = State.QUEUED, progress = 0f, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An HTTP exception can contain a signed stream URL. Keep it out of the UI and index.
            if (!completed) update(entry) {
                it.copy(state = State.FAILED, progress = 0f, error = "Download failed. Check your connection and retry.")
            }
        } finally {
            if (!completed) directory.deleteRecursively()
            synchronized(this) {
                jobs.remove(entry.workId)
                // A listener can take ownership of an automatic copy while its download is deferred.
                if (deferred) _entries.value[entry.song.videoId]
                    ?.takeIf { it.workId == entry.workId && !it.smartDownload && it.state == State.QUEUED }
                    ?.let(::start)
            }
            if (entry.smartDownload && !deferred) resumeSmart()
        }
    }

    private fun requireSmartConditions(entry: Entry) {
        val current = _entries.value[entry.song.videoId]
        if (current?.workId == entry.workId && current.smartDownload && !DesktopSmartDownloads.allowed()) {
            throw SmartDownloadDeferred()
        }
    }

    private suspend fun fetch(entry: Entry, part: File): Triple<Streams.Kind, Streams.Stream, Long> {
        val preferred = if (entry.quality == AudioQuality.HIGH || entry.quality == AudioQuality.MAX) Streams.Kind.OPUS else Streams.Kind.AAC
        val id = entry.song.videoId
        var failure: Exception? = null
        for (fresh in listOf(false, true)) {
            requireSmartConditions(entry)
            val choices = Streams.downloadChoices(id, entry.quality.maxKbps, preferred, fresh)
            for ((kind, stream) in choices) {
                try {
                    requireSmartConditions(entry)
                    update(entry, persist = false) { it.copy(progress = 0f) }
                    RangedFetch.fetch(Http.client, stream.url, stream.headers, part, maxBytes = 512L * 1024 * 1024) { progress ->
                        requireSmartConditions(entry)
                        update(entry, persist = false) { it.copy(progress = progress.coerceIn(0f, 0.99f)) }
                    }
                    return Triple(kind, stream, validate(part))
                } catch (deferred: SmartDownloadDeferred) {
                    throw deferred
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    failure = e
                }
            }
        }
        throw IOException("Audio sources failed", failure)
    }

    private fun validate(part: File): Long {
        check(Ffmpeg.available) { "The audio decoder could not start" }
        return FFmpegFrameGrabber(part).use { grabber ->
            grabber.start()
            check(grabber.hasAudio() && grabber.grabSamples() != null) { "The downloaded file contains no readable audio" }
            (grabber.lengthInTime / 1000).coerceAtLeast(0L)
        }
    }

    private fun saveCover(entry: Entry, directory: File) {
        val url = entry.song.thumbnailUrl ?: return
        val cover = File(directory, "cover")
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful || !response.header("Content-Type").orEmpty().startsWith("image/")) return
                val data = response.body?.byteStream()?.readNBytes(2 * 1024 * 1024 + 1) ?: return
                if (data.size in 1..2 * 1024 * 1024) cover.writeBytes(data)
            }
        }.onFailure { cover.delete() }
    }

    @Synchronized
    private fun update(entry: Entry, persist: Boolean = true, transform: (Entry) -> Entry): Boolean {
        val id = entry.song.videoId
        val current = _entries.value[id]?.takeIf { it.workId == entry.workId } ?: return false
        val next = _entries.value + (id to transform(current))
        if (persist) {
            if (save(next)) return true
            _entries.value = _entries.value + (id to current.copy(state = State.FAILED, progress = 0f, error = _error.value))
            return false
        }
        _entries.value = next
        return true
    }

    private fun readIndex(): Map<String, Entry> {
        if (indexLock.getOrNull() != null) return index.read(emptyMap()) { json.decodeFromString(it) }
        return runCatching { json.decodeFromString<Map<String, Entry>>(index.file.readText()) }.getOrDefault(emptyMap())
    }

    private fun save(entries: Map<String, Entry>): Boolean {
        if (indexLock.getOrNull() == null) {
            _error.value = if (indexLock.isSuccess) "Downloads are being managed by another OpenTune window."
            else "Could not open the download library. Check the folder permissions."
            return false
        }
        return try {
            val part = File(root, "index.json.part")
            part.writeText(json.encodeToString(entries))
            part.moveTo(index.file)
            _entries.value = entries
            _error.value = null
            true
        } catch (_: IOException) {
            _error.value = "Could not save the download library. Check the available disk space and permissions."
            false
        }
    }

    private fun File.moveTo(target: File) {
        try {
            Files.move(toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }
}
