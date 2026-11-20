package com.opentune.desktop

import com.opentune.data.isYouTubeId
import java.awt.Desktop
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal object DesktopLinks {
    sealed interface Link {
        data class Track(val id: String) : Link
        data class Playlist(val id: String) : Link
        data class Room(val code: String) : Link
    }

    private val pending = Channel<Link>(Channel.UNLIMITED)
    val links = pending.receiveAsFlow()
    private val open = Channel<Unit>(Channel.CONFLATED)
    val openRequests = open.receiveAsFlow()

    fun requestOpen() { open.trySend(Unit) }
    fun submit(text: String): Boolean {
        val parsed = parse(text) ?: return false
        return pending.trySend(parsed).isSuccess
    }

    fun install(arguments: Array<String>, show: () -> Unit) {
        arguments.forEach(::submit)
        if (!Desktop.isDesktopSupported()) return
        runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { if (submit(it.uri.toString())) show() }
        }
    }

    internal fun parse(value: String): Link? {
        val url = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val host = url.host?.lowercase() ?: return null
        val scheme = url.scheme?.lowercase()
        val path = url.path.orEmpty()
        if (scheme == "opentune" && host == "together") return Link.Room(value.trim())
        if (scheme !in setOf("https", "http") || url.userInfo != null) return null
        if (host !in setOf("youtu.be", "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")) return null
        val query = url.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val parts = parameter.split('=', limit = 2)
            if (parts.size != 2) null else parts[0] to runCatching { URLDecoder.decode(parts[1], Charsets.UTF_8) }.getOrNull()
        }.toMap()
        val video = when {
            host == "youtu.be" -> path.removePrefix("/")
            path == "/watch" -> query["v"]
            path.startsWith("/shorts/") || path.startsWith("/live/") -> path.substringAfterLast('/')
            else -> null
        }
        val playlist = query["list"] ?: path.removePrefix("/browse/").takeIf { path.startsWith("/browse/VL") }
        if (path == "/playlist" || video == null) {
            playlist?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,100}")) }?.let {
                return Link.Playlist(if (it.startsWith("VL")) it else "VL$it")
            }
        }
        return video?.takeIf(::isYouTubeId)?.let { Link.Track(it) }
    }
}
