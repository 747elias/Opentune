package com.opentune.desktop

import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import java.util.UUID
import javax.swing.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access.READ
import org.freedesktop.dbus.annotations.DBusProperty.Access.READ_WRITE
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal.FALSE
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.InvalidMethodArgument
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

private const val PATH = "/org/mpris/MediaPlayer2"
private const val ROOT = "org.mpris.MediaPlayer2"
private const val PLAYER = "$ROOT.Player"
private const val BUS_NAME = "$ROOT.opentune"

internal interface MprisMetadata : TypeRef<Map<String, Variant<*>>>

@DBusInterfaceName(ROOT)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = READ)
@DBusProperty(name = "Identity", type = String::class, access = READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = READ)
@DBusProperty(name = "SupportedUriSchemes", type = Array<String>::class, access = READ)
@DBusProperty(name = "SupportedMimeTypes", type = Array<String>::class, access = READ)
@Suppress("FunctionName")
internal interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(PLAYER)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = READ)
@DBusProperty(name = "LoopStatus", type = String::class, access = READ_WRITE)
@DBusProperty(name = "Rate", type = Double::class, access = READ_WRITE)
@DBusProperty(name = "Shuffle", type = Boolean::class, access = READ_WRITE)
@DBusProperty(name = "Metadata", type = MprisMetadata::class, access = READ)
@DBusProperty(name = "Volume", type = Double::class, access = READ_WRITE)
@DBusProperty(name = "Position", type = Long::class, access = READ, emitChangeSignal = FALSE)
@DBusProperty(name = "MinimumRate", type = Double::class, access = READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = READ, emitChangeSignal = FALSE)
@Suppress("FunctionName")
internal interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/** Publishes the desktop player to playerctl, media keys and status bars on the Linux session bus. */
internal object LinuxMpris {
    suspend fun serve() {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) return
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")?.takeIf { it.isNotBlank() }
            ?: System.getenv("XDG_RUNTIME_DIR")?.let { File(it, "bus") }?.takeIf { it.exists() }?.let { "unix:path=$it" }
            ?: return
        withContext(Dispatchers.IO) {
            try {
                val builder = DBusConnectionBuilder.forAddress(address).withShared(false)
                builder.receivingThreadConfig().withMethodCallThreadCount(1)
                builder.build().use { bus ->
                    val service = MprisService()
                    val initial = service.playerProperties(includePosition = false)
                    bus.exportObject(service)
                    bus.requestBusName(BUS_NAME)
                    coroutineScope {
                        launch {
                            var previous = initial
                            service.changes.collect {
                                val updated = service.playerProperties(includePosition = false)
                                val changed = updated.filter { (name, value) -> previous[name] != value }
                                if (changed.isNotEmpty()) bus.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, changed, emptyList()))
                                previous = updated
                            }
                        }
                        launch {
                            Player.seeks.collect { bus.sendMessage(MprisPlayer.Seeked(PATH, micros(it))) }
                        }
                        awaitCancellation()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("MPRIS: ${e.message}")
            }
        }
    }
}

private fun micros(ms: Long): Long = ms.coerceIn(0, Long.MAX_VALUE / 1000) * 1000

/** D-Bus actions run on the same thread as the corresponding player and window buttons. */
internal class MprisService : MprisRoot, MprisPlayer, Properties {
    val changes: Flow<Any?> = merge(
        Player.playing, Player.loading, Player.stopped, Player.error, Player.queue, Player.index, Player.durationOf,
        Player.repeat, Player.shuffled, Player.volume, Player.quality, AppSettings.sound, AppSettings.playback,
    )

    private val noTrack = DBusPath("$PATH/TrackList/NoTrack")
    private val rootProperties = mapOf(
        "CanQuit" to Variant(true), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
        "Identity" to Variant("OpenTune"), "DesktopEntry" to Variant("opentune"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    override fun getObjectPath() = PATH

    override fun Raise() = EventQueue.invokeLater {
        DesktopWindow.window?.let { window ->
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            window.toFront()
        }
    }

    override fun Quit() = EventQueue.invokeLater {
        // Keep the bus connected long enough to send Quit's reply before closing the window.
        Timer(100) { DesktopWindow.close() }.apply { isRepeats = false; start() }
    }
    override fun Next() = EventQueue.invokeLater { if (canGoNext()) Player.next() }
    override fun Previous() = EventQueue.invokeLater { if (Player.current != null) Player.previous() }
    override fun Pause() = EventQueue.invokeLater { Player.pause() }
    override fun PlayPause() = EventQueue.invokeLater { Player.toggle() }
    override fun Stop() = EventQueue.invokeLater { Player.stop() }
    override fun Play() = EventQueue.invokeLater { Player.play() }

    override fun Seek(offset: Long) = EventQueue.invokeLater {
        if (!canSeek()) return@invokeLater
        val target = Player.positionMs() + offset / 1000
        if (target > Player.durationMs()) {
            if (canGoNext()) Player.next()
        } else {
            Player.seekTo(target.coerceAtLeast(0))
        }
    }

    override fun SetPosition(trackId: DBusPath, position: Long) = EventQueue.invokeLater {
        val song = Player.current ?: return@invokeLater
        if (canSeek() && trackId.path == trackPath(song, Player.index.value).path && position in 0..micros(Player.durationMs())) {
            Player.seekTo(position / 1000)
        }
    }

    override fun OpenUri(uri: String) {
        throw InvalidMethodArgument("OpenTune does not open media URIs")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A = GetAll(interfaceName)[propertyName] as A

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        ROOT -> rootProperties
        PLAYER -> playerProperties(includePosition = true)
        else -> emptyMap()
    }

    override fun <A> Set(interfaceName: String, propertyName: String, newValue: A) {
        if (interfaceName != PLAYER) throw InvalidMethodArgument("Unknown property $propertyName")
        val value = if (newValue is Variant<*>) newValue.value else newValue
        when (propertyName) {
            "LoopStatus" -> {
                val repeat = when (value) {
                    "None" -> Player.Repeat.OFF
                    "Track" -> Player.Repeat.ONE
                    "Playlist" -> Player.Repeat.ALL
                    else -> throw InvalidMethodArgument("Invalid LoopStatus")
                }
                EventQueue.invokeLater { Player.setRepeat(repeat) }
            }
            "Rate" -> {
                val rate = value as? Double ?: throw InvalidMethodArgument("Invalid Rate")
                if (rate != 0.0 && rate !in minimumRate()..maximumRate()) throw InvalidMethodArgument("Unsupported Rate")
                EventQueue.invokeLater {
                    if (rate == 0.0) Player.pause() else AppSettings.updateSound { it.copy(speed = rate.toFloat()) }
                }
            }
            "Shuffle" -> {
                val shuffle = value as? Boolean ?: throw InvalidMethodArgument("Invalid Shuffle")
                EventQueue.invokeLater { if (shuffle != Player.shuffled.value) Player.toggleShuffle() }
            }
            "Volume" -> {
                val volume = (value as? Double)?.takeIf { it.isFinite() } ?: throw InvalidMethodArgument("Invalid Volume")
                EventQueue.invokeLater { Player.setVolume(volume) }
            }
            else -> throw InvalidMethodArgument("Property $propertyName is not writable")
        }
    }

    private fun canGoNext(): Boolean {
        val queue = Player.queue.value
        val index = Player.index.value
        return index in queue.indices && (index < queue.lastIndex || Player.repeat.value == Player.Repeat.ALL ||
            (AppSettings.playback.value.autoplay && isYouTubeId(queue[index].videoId)))
    }

    private fun canSeek() = Player.current != null && !Player.loading.value && !Player.stopped.value &&
        Player.error.value == null && Player.durationMs() > 0
    private fun lossless() = Player.quality.value?.lossless == true
    private fun minimumRate() = if (lossless()) 1.0 else 0.5
    private fun maximumRate() = if (lossless()) 1.0 else 2.0

    private fun trackPath(song: Song, index: Int): DBusPath {
        val id = UUID.nameUUIDFromBytes(song.videoId.encodeToByteArray()).toString().replace("-", "")
        return DBusPath("$PATH/track/${index}_$id")
    }

    private fun metadata(song: Song?, index: Int): Map<String, Variant<*>> = buildMap {
        put("mpris:trackid", Variant(song?.let { trackPath(it, index) } ?: noTrack))
        if (song == null) return@buildMap
        put("xesam:title", Variant(song.title))
        put("xesam:artist", Variant(listOfNotNull(song.artist.takeIf(String::isNotBlank)), "as"))
        song.albumName?.let { put("xesam:album", Variant(it)) }
        song.thumbnailUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { put("mpris:artUrl", Variant(it)) }
        val (knownId, knownMs) = Player.durationOf.value
        val ms = knownMs.takeIf { knownId == song.videoId && it > 0 }
            ?: song.durationText?.let(Player::seconds)?.let { (it * 1000).toLong() }
        ms?.let { put("mpris:length", Variant(micros(it))) }
    }

    fun playerProperties(includePosition: Boolean): Map<String, Variant<*>> = buildMap {
        val index = Player.index.value
        val song = Player.queue.value.getOrNull(index)
        val stopped = song == null || Player.stopped.value || Player.error.value != null
        val status = when {
            stopped -> "Stopped"
            Player.playing.value -> "Playing"
            else -> "Paused"
        }
        val repeat = when (Player.repeat.value) {
            Player.Repeat.OFF -> "None"
            Player.Repeat.ONE -> "Track"
            Player.Repeat.ALL -> "Playlist"
        }
        put("PlaybackStatus", Variant(status))
        put("LoopStatus", Variant(repeat))
        put("Rate", Variant(if (lossless()) 1.0 else AppSettings.sound.value.speed.toDouble().coerceIn(0.5, 2.0)))
        put("Shuffle", Variant(Player.shuffled.value))
        put("Metadata", Variant(metadata(song, index), "a{sv}"))
        put("Volume", Variant(Player.volume.value))
        if (includePosition) put("Position", Variant(if (stopped) 0L else micros(Player.positionMs())))
        put("MinimumRate", Variant(minimumRate()))
        put("MaximumRate", Variant(maximumRate()))
        put("CanGoNext", Variant(canGoNext()))
        put("CanGoPrevious", Variant(song != null))
        put("CanPlay", Variant(song != null))
        put("CanPause", Variant(song != null))
        put("CanSeek", Variant(canSeek()))
        put("CanControl", Variant(true))
    }
}
