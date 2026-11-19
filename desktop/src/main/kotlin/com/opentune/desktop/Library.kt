package com.opentune.desktop

import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Liked songs, your playlists, every song played and the volume, kept in an
 * SQLite database in the user's own folder: ~/.opentune/library.db. Each
 * change is one transaction, so a crash mid-write never leaves it half
 * saved, and the full play history is kept rather than the last few.
 *
 * The library the desktop app kept before, ~/.opentune/desktop.json, is
 * read into it once and then kept as desktop.json.imported.
 */
object Library {
    @Serializable
    data class Saved(
        val id: String, val title: String, val artist: String, val cover: String? = null, val length: String? = null,
        val album: String? = null, val explicit: Boolean? = null, val artistId: String? = null,
    ) {
        fun toSong() = Song(id, title, artist, cover, length, artistId = artistId, albumName = album, isExplicit = explicit)
    }

    @Serializable
    data class Playlist(val id: String, val name: String, val songs: List<Saved> = emptyList()) {
        /** Up to four different covers, for the playlist's tile. */
        val covers: List<String> get() = songs.mapNotNull { it.cover }.distinct().take(4)
    }

    /** The old JSON file's shape, for reading it in once. */
    @Serializable
    private data class Legacy(
        val liked: List<Saved> = emptyList(),
        val recent: List<Saved> = emptyList(),
        val volume: Double = 0.8,
        val playlists: List<Playlist> = emptyList(),
    )

    /** How many recent songs the pages list; the history itself keeps every play. */
    private const val RECENT = 50
    private const val TRACK_COLUMNS = "s.id, s.title, s.artist, s.cover, s.length, s.album, s.explicit, s.artist_id"
    private val json = Json { ignoreUnknownKeys = true }
    private var legacy = File(System.getProperty("user.home"), ".opentune/desktop.json")
    private var db: Connection = open(legacy)

    private val _liked = MutableStateFlow<List<Song>>(emptyList())
    val liked: StateFlow<List<Song>> = _liked.asStateFlow()
    private val _recent = MutableStateFlow<List<Song>>(emptyList())
    val recent: StateFlow<List<Song>> = _recent.asStateFlow()
    private val _playHistoryRevision = MutableStateFlow(0)
    internal val playHistoryRevision: StateFlow<Int> = _playHistoryRevision.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _localTracks = MutableStateFlow<List<Song>>(emptyList())
    val localTracks: StateFlow<List<Song>> = _localTracks.asStateFlow()
    @Volatile private var volume = 0.8

    init { publish() }

    /** For tests: keeps the library beside [at] (the old JSON file's place) instead. */
    @Synchronized
    internal fun useFile(at: File) {
        runCatching { db.close() }
        legacy = at
        db = open(at)
        publish()
        _playHistoryRevision.update { it + 1 }
    }

    fun isLiked(id: String) = _liked.value.any { it.videoId == id }

    fun setLiked(song: Song, liked: Boolean) = write {
        if (liked) {
            keep(song.saved())
            exec("INSERT OR REPLACE INTO liked(id, at) VALUES (?, ?)", song.videoId, System.currentTimeMillis())
        } else {
            exec("DELETE FROM liked WHERE id = ?", song.videoId)
        }
    }

    fun played(song: Song) {
        write {
            keep(song.saved())
            exec("INSERT INTO plays(id, at) VALUES (?, ?)", song.videoId, System.currentTimeMillis())
        }
        _playHistoryRevision.update { it + 1 }
    }

    /** A new, empty playlist; its id is returned. */
    fun createPlaylist(name: String): String {
        val id = "pl-" + java.util.UUID.randomUUID().toString().take(8)
        write {
            val next = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlists") { it.getInt(1) }.first()
            exec("INSERT INTO playlists(id, name, position) VALUES (?, ?, ?)", id, name.trim().ifEmpty { "New playlist" }, next)
        }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        write { exec("UPDATE playlists SET name = ? WHERE id = ?", clean, id) }
    }

    fun deletePlaylist(id: String) = write {
        exec("DELETE FROM playlist_songs WHERE playlist = ?", id)
        exec("DELETE FROM playlists WHERE id = ?", id)
    }

    /** Adds [song] at the end, unless it's already there. */
    fun addToPlaylist(id: String, song: Song) = write {
        val there = query("SELECT 1 FROM playlist_songs WHERE playlist = ? AND id = ?", id, song.videoId) { 1 }.isNotEmpty()
        if (!there) {
            keep(song.saved())
            val next = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist = ?", id) { it.getInt(1) }.first()
            exec("INSERT INTO playlist_songs(playlist, position, id) VALUES (?, ?, ?)", id, next, song.videoId)
        }
    }

    fun removeFromPlaylist(id: String, videoId: String) = write {
        exec("DELETE FROM playlist_songs WHERE playlist = ? AND id = ?", id, videoId)
    }

    fun addLocalTracks(songs: List<Song>) = write {
        songs.filter { LocalMusic.isLocal(it.videoId) }.forEach { song ->
            keep(song.saved())
            exec("INSERT OR REPLACE INTO local_tracks(id, album) VALUES (?, ?)", song.videoId, song.albumName)
        }
    }

    fun removeLocalTrack(id: String) = write {
        exec("DELETE FROM local_tracks WHERE id = ?", id)
    }

    /**
     * Every play, newest first, as the phone's history keeps them, for the
     * stats, mixes and speed dial the shared code makes from it. At most
     * [limit], read fresh each time.
     */
    @Synchronized
    fun history(limit: Int = 10_000): List<com.opentune.data.history.PlayRecord> = runCatching {
        db.query(
            "SELECT $TRACK_COLUMNS, p.at, p.listened FROM plays p JOIN songs s ON s.id = p.id ORDER BY p.at DESC, p.rowid DESC LIMIT ?",
            limit,
        ) { r ->
            val track = saved(r)
            com.opentune.data.history.PlayRecord(track.id, track.title, track.artist, track.cover, track.length, track.album,
                playedAt = r.getLong(9), listenedMs = r.getLong(10), explicit = track.explicit, artistId = track.artistId)
        }
    }.getOrDefault(emptyList())

    fun volume(): Double = volume
    fun setVolume(v: Double) = write { exec("INSERT OR REPLACE INTO settings(key, value) VALUES ('volume', ?)", v.toString()) }

    /** Saves one complete imported playlist in one transaction. */
    @Synchronized
    internal fun importPlaylist(name: String, songs: List<Song>): String {
        val id = "pl-" + java.util.UUID.randomUUID()
        transaction { importList(id, name, songs) }
        publish()
        return id
    }

    @Synchronized
    internal fun restoreBackup(collections: DesktopBackup.Collections?, history: List<com.opentune.data.history.PlayRecord>) {
        transaction {
            val now = System.currentTimeMillis()
            collections?.liked.orEmpty().forEachIndexed { index, track ->
                val song = track.song()
                if (song.videoId.isNotBlank()) {
                    keep(song.saved())
                    exec("INSERT OR IGNORE INTO liked(id, at) VALUES (?, ?)", song.videoId, now - index)
                }
            }
            collections?.playlists.orEmpty().forEach { list ->
                if (list.id.isNotBlank()) importList(list.id, list.name, list.songs.map { it.song() })
            }
            history.asReversed().forEach { record ->
                if (record.videoId.isNotBlank() && record.playedAt > 0) {
                    keep(record.toSong().saved())
                    exec("INSERT INTO plays(id, at, listened) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM plays WHERE id = ? AND at = ?)",
                        record.videoId, record.playedAt, record.listenedMs.coerceAtLeast(0), record.videoId, record.playedAt)
                }
            }
        }
        publish()
        if (history.isNotEmpty()) _playHistoryRevision.update { it + 1 }
    }

    private fun Connection.importList(id: String, name: String, songs: List<Song>) {
        val next = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlists") { it.getInt(1) }.first()
        exec("INSERT OR IGNORE INTO playlists(id, name, position) VALUES (?, ?, ?)", id, name.trim().ifEmpty { "Imported playlist" }, next)
        val existing = query("SELECT id FROM playlist_songs WHERE playlist = ? ORDER BY position", id) { it.getString(1) }.toMutableSet()
        var at = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist = ?", id) { it.getInt(1) }.first()
        for (song in songs) {
            if (song.videoId.isBlank() || !existing.add(song.videoId)) continue
            keep(song.saved())
            exec("INSERT INTO playlist_songs(playlist, position, id) VALUES (?, ?, ?)", id, at++, song.videoId)
        }
    }

    private fun Song.saved() = Saved(videoId, title, artist, thumbnailUrl, durationText, albumName, isExplicit, artistId)

    // ---- The database -------------------------------------------------------------

    /** Runs [change] as one transaction, then shows the result. A failed write leaves the library as it was. */
    @Synchronized
    private fun write(change: Connection.() -> Unit) {
        runCatching { transaction(change) }.onFailure { System.err.println("Library: couldn't save: $it") }
        publish()
    }

    private fun transaction(change: Connection.() -> Unit) {
        db.autoCommit = false
        try {
            db.change()
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    /** Every song's details, once, however many lists it's on. */
    private fun Connection.keep(s: Saved) =
        exec(
            """INSERT INTO songs(id, title, artist, cover, length, album, explicit, artist_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET title = excluded.title, artist = excluded.artist,
                cover = COALESCE(excluded.cover, songs.cover), length = COALESCE(excluded.length, songs.length),
                album = COALESCE(excluded.album, songs.album), explicit = COALESCE(excluded.explicit, songs.explicit),
                artist_id = COALESCE(excluded.artist_id, songs.artist_id)""".trimIndent(),
            s.id, s.title, s.artist, s.cover, s.length, s.album, s.explicit?.let { if (it) 1 else 0 }, s.artistId,
        )

    @Synchronized
    private fun publish() {
        runCatching {
            _liked.value = db.query("SELECT $TRACK_COLUMNS FROM liked l JOIN songs s ON s.id = l.id ORDER BY l.at DESC, l.rowid DESC", row = ::song)
            _recent.value = db.query(
                "SELECT $TRACK_COLUMNS FROM (SELECT id, MAX(rowid) AS last FROM plays GROUP BY id) p " +
                    "JOIN songs s ON s.id = p.id ORDER BY p.last DESC LIMIT $RECENT",
                row = ::song,
            )
            _localTracks.value = db.query(
                "SELECT $TRACK_COLUMNS, l.album FROM local_tracks l JOIN songs s ON s.id = l.id " +
                    "ORDER BY s.title COLLATE NOCASE, s.artist COLLATE NOCASE, s.id",
            ) { r -> song(r).copy(albumName = r.getString(9) ?: r.getString(6)) }
            val lists = db.query("SELECT id, name FROM playlists ORDER BY position") { it.getString(1) to it.getString(2) }
            _playlists.value = lists.map { (id, name) ->
                Playlist(
                    id, name,
                    db.query(
                        "SELECT $TRACK_COLUMNS FROM playlist_songs p JOIN songs s ON s.id = p.id WHERE p.playlist = ? ORDER BY p.position",
                        id, row = ::saved,
                    ),
                )
            }
            volume = db.query("SELECT value FROM settings WHERE key = 'volume'") { it.getString(1).toDoubleOrNull() }.firstOrNull() ?: 0.8
        }.onFailure { System.err.println("Library: couldn't read: $it") }
    }

    private fun song(r: java.sql.ResultSet) = saved(r).toSong()

    private fun saved(r: java.sql.ResultSet): Saved {
        val explicit = (r.getObject(7) as? Number)?.toInt()?.let { it != 0 }
        return Saved(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), explicit, r.getString(8))
    }

    private fun open(legacyFile: File): Connection {
        val folder = legacyFile.absoluteFile.parentFile.apply { mkdirs() }
        val c = DriverManager.getConnection("jdbc:sqlite:" + File(folder, "library.db").absolutePath)
        c.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode = WAL")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS songs(id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, cover TEXT, length TEXT)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS local_tracks(id TEXT PRIMARY KEY, album TEXT)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS liked(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plays(id TEXT NOT NULL, at INTEGER NOT NULL)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS plays_by_id ON plays(id)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlists(id TEXT PRIMARY KEY, name TEXT NOT NULL, position INTEGER NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_songs(playlist TEXT NOT NULL, position INTEGER NOT NULL, id TEXT NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)")
        }
        val columns = c.query("PRAGMA table_info(songs)") { it.getString("name") }.toSet()
        if ("album" !in columns) c.exec("ALTER TABLE songs ADD COLUMN album TEXT")
        if ("explicit" !in columns) c.exec("ALTER TABLE songs ADD COLUMN explicit INTEGER")
        if ("artist_id" !in columns) c.exec("ALTER TABLE songs ADD COLUMN artist_id TEXT")
        if ("listened" !in c.query("PRAGMA table_info(plays)") { it.getString("name") }) {
            c.exec("ALTER TABLE plays ADD COLUMN listened INTEGER NOT NULL DEFAULT 0")
        }
        importLegacy(c, legacyFile)
        return c
    }

    /** The old JSON library, read in once; the file is kept aside, not deleted. */
    private fun importLegacy(c: Connection, file: File) {
        if (!file.isFile) return
        val old = runCatching { json.decodeFromString(Legacy.serializer(), file.readText()) }.getOrNull() ?: return
        c.autoCommit = false
        try {
            val now = System.currentTimeMillis()
            fun keep(s: Saved) = c.exec("INSERT OR REPLACE INTO songs(id, title, artist, cover, length) VALUES (?, ?, ?, ?, ?)", s.id, s.title, s.artist, s.cover, s.length)
            // Newest first in the file: given falling times so the order holds.
            old.liked.forEachIndexed { i, s -> keep(s); c.exec("INSERT OR IGNORE INTO liked(id, at) VALUES (?, ?)", s.id, now - i) }
            old.recent.asReversed().forEachIndexed { i, s -> keep(s); c.exec("INSERT INTO plays(id, at) VALUES (?, ?)", s.id, now - old.recent.size + i) }
            old.playlists.forEachIndexed { at, p ->
                c.exec("INSERT OR IGNORE INTO playlists(id, name, position) VALUES (?, ?, ?)", p.id, p.name, at)
                p.songs.forEachIndexed { i, s -> keep(s); c.exec("INSERT INTO playlist_songs(playlist, position, id) VALUES (?, ?, ?)", p.id, i, s.id) }
            }
            c.exec("INSERT OR REPLACE INTO settings(key, value) VALUES ('volume', ?)", old.volume.toString())
            c.commit()
            file.renameTo(File(file.parentFile, file.name + ".imported"))
        } catch (e: Exception) {
            c.rollback()
            System.err.println("Library: couldn't read the old library in: $e")
        } finally {
            c.autoCommit = true
        }
    }

    private fun Connection.exec(sql: String, vararg args: Any?) {
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun <T> Connection.query(sql: String, vararg args: Any?, row: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { r -> buildList { while (r.next()) add(row(r)) } }
        }
}
