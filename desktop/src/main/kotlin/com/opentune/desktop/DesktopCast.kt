package com.opentune.desktop

import com.opentune.cast.CastMedia
import com.opentune.cast.CastServer
import com.opentune.cast.CastTarget
import com.opentune.cast.Chromecast
import com.opentune.cast.Dlna
import com.opentune.cast.DlnaDiscovery
import com.opentune.cast.Receiver
import com.opentune.cast.RemoteState
import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import com.opentune.data.radio.Radio
import com.opentune.data.subsonic.Subsonic
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import javax.jmdns.JmDNS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object DesktopCast {
    class Connection(val target: CastTarget, val receiver: Receiver, val address: InetAddress)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val connected = MutableStateFlow<Connection?>(null)
    val active = connected.asStateFlow()
    private val _targets = MutableStateFlow<List<CastTarget>>(emptyList())
    val targets = _targets.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun discover() {
        if (_busy.value) return
        _targets.value = emptyList()
        _error.value = null
        _busy.value = true
        scope.launch {
            fun add(target: CastTarget) { _targets.update { (it.filterNot { old -> old.id == target.id } + target).sortedBy(CastTarget::name) } }
            try {
                coroutineScope {
                    launch { runCatching { DlnaDiscovery.find(3_500, ::add) } }
                    val interfaces = NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                    interfaces.flatMap { it.inetAddresses().toList() }.filterIsInstance<Inet4Address>().forEach { address ->
                        launch {
                            runCatching {
                                JmDNS.create(address).use { dns ->
                                    dns.list("_googlecast._tcp.local.", 3_500).forEach { service ->
                                        val host = service.inet4Addresses.firstOrNull()?.hostAddress ?: return@forEach
                                        val id = service.getPropertyString("id") ?: service.qualifiedName
                                        add(CastTarget(id, service.getPropertyString("fn") ?: service.name, CastTarget.Kind.CHROMECAST,
                                            host, service.port, service.getPropertyString("md")))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                _error.value = error.localizedMessage ?: "Couldn't search this network."
            } finally { _busy.value = false }
        }
    }

    fun connect(target: CastTarget) {
        scope.launch {
            gate.withLock {
                _busy.value = true
                _error.value = null
                var receiver: Receiver? = null
                try {
                    val address = when (target.kind) {
                        CastTarget.Kind.CHROMECAST -> Chromecast(target.name, target.host, target.port).also {
                            receiver = it
                            it.connect()
                        }.localAddress
                        CastTarget.Kind.DLNA -> requireNotNull(target.dlna).let { device ->
                            Dlna(target.name, device.transportUrl, device.renderingUrl).also { receiver = it }.localAddress
                        }
                    } ?: error("Could not reach ${target.name} on this network.")
                    val connection = Connection(target, requireNotNull(receiver), address)
                    val existing = connected.value
                    existing?.receiver?.close()
                    CastServer.stop()
                    try {
                        CastServer.start(address)
                    } catch (error: Exception) {
                        connected.value = null
                        if (existing != null) Player.outputChanged()
                        throw error
                    }
                    connected.value = connection
                    Player.outputChanged()
                } catch (cancelled: CancellationException) {
                    receiver?.close()
                    throw cancelled
                } catch (error: Exception) {
                    receiver?.close()
                    _error.value = error.localizedMessage ?: "Couldn't connect to ${target.name}."
                } finally { _busy.value = false }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            gate.withLock {
                val previous = connected.value ?: return@withLock
                connected.value = null
                Player.outputChanged()
                previous.receiver.close()
                CastServer.stop()
            }
        }
    }

    suspend fun media(connection: Connection, song: Song): CastMedia = withContext(Dispatchers.IO) {
        val id = song.videoId
        val local = OfflineDownloads.audio(id)?.file ?: DesktopLocalMusic.fileOf(song)
        val source = when {
            local != null -> CastServer.Source.OnPhone(local, contentType(local))
            Radio.isRadio(id) -> CastServer.Source.Web("audio/mpeg") { requireNotNull(Radio.streamUrl(id)) to emptyMap() }
            Subsonic.isSubsonic(id) -> CastServer.Source.Web("audio/mpeg") {
                requireNotNull(Subsonic.streamUrl(id)).replace("format=raw", "format=mp3&maxBitRate=320") to emptyMap()
            }
            isYouTubeId(id) -> CastServer.Source.Web("audio/mp4") { fresh -> Streams.resolve(id, fresh).let { it.url to it.headers } }
            else -> error("This song cannot be cast.")
        }
        CastMedia(id, CastServer.publish(source, connection.address), source.contentType, song.title, song.artist,
            song.albumName, song.thumbnailUrl, song.durationMillis(), live = Radio.isRadio(id))
    }

    private fun contentType(file: File): String = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> Files.probeContentType(file.toPath()) ?: "application/octet-stream"
    }
}

internal class CastEngine(
    private val connection: DesktopCast.Connection,
    private val song: Song,
    private val startAt: Long,
    initiallyPlaying: Boolean,
    private val ready: (Long) -> Unit,
    private val started: () -> Unit,
    private val playing: (Boolean) -> Unit,
    private val ended: () -> Unit,
    private val failed: (String?) -> Unit,
) : Engine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiver = connection.receiver
    private val commands = Mutex()
    @Volatile private var loaded = false
    @Volatile private var requestedPlay = initiallyPlaying
    @Volatile private var position = startAt
    @Volatile private var duration = 0L
    @Volatile private var volume = 1f
    @Volatile private var estimateAt = 0L
    @Volatile private var advancing = false

    override fun begin() {
        scope.launch {
            try {
                commands.withLock {
                    val media = DesktopCast.media(connection, song)
                    duration = media.durationMs
                    receiver.load(media, position, requestedPlay)
                    receiver.setVolume(volume)
                    loaded = true
                }
                ready(duration)
                var audible = false
                var completed = false
                receiver.status.collect { state ->
                    if (state.mediaId != null && state.mediaId != song.videoId) return@collect
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (state.positionKnown && state.at > 0) position = state.positionNow(now)
                    else position = positionMs()
                    estimateAt = now
                    advancing = state.state == RemoteState.PLAYING
                    if (advancing && !audible) { audible = true; started() }
                    playing(advancing)
                    when (state.state) {
                        RemoteState.FINISHED -> if (audible && !completed) { completed = true; ended() }
                        RemoteState.ERROR, RemoteState.GONE -> if (!completed) { completed = true; failed("The receiver stopped responding.") }
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(error.localizedMessage ?: "Couldn't play on the receiver.")
            }
        }
    }

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            commands.withLock {
                if (loaded) try { block() } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failed(error.localizedMessage) }
            }
        }
    }

    override fun play() { requestedPlay = true; command { receiver.play() } }
    override fun pause() { requestedPlay = false; command { receiver.pause() } }
    override fun seek(ms: Long) {
        position = ms.coerceAtLeast(0)
        estimateAt = android.os.SystemClock.elapsedRealtime()
        command { receiver.seek(ms) }
    }
    override fun positionMs(): Long = position + if (advancing) (android.os.SystemClock.elapsedRealtime() - estimateAt).coerceAtLeast(0) else 0
    override fun durationMs(): Long = duration
    override fun setVolume(v: Double) { volume = v.coerceIn(0.0, 1.0).toFloat(); command { receiver.setVolume(volume) } }
    override fun dispose() = scope.cancel()
}
