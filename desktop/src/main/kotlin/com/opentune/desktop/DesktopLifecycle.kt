package com.opentune.desktop

import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/** Finder file events and the macOS distinction between closing a window and quitting. */
internal object DesktopLifecycle {
    private val audio = Channel<List<File>>(Channel.UNLIMITED)
    val audioRequests = audio.receiveAsFlow()

    private val desktop by lazy {
        if (DesktopWindow.isMac && Desktop.isDesktopSupported()) runCatching { Desktop.getDesktop() }.getOrNull() else null
    }
    val canReopen: Boolean get() = desktop?.isSupported(Desktop.Action.APP_EVENT_REOPENED) == true

    fun install(arguments: Array<String>, show: () -> Unit, quit: () -> Unit): AutoCloseable {
        openAudio(arguments.map(::File).filter { it.isFile })

        val platform = desktop ?: return AutoCloseable {}
        val reopen = AppReopenedListener { show() }
        val supportsFiles = platform.isSupported(Desktop.Action.APP_OPEN_FILE)
        val supportsQuit = platform.isSupported(Desktop.Action.APP_QUIT_HANDLER)
        if (canReopen) platform.addAppEventListener(reopen)
        if (supportsFiles) platform.setOpenFileHandler { event ->
            show()
            openAudio(event.files)
        }
        if (supportsQuit) platform.setQuitHandler { _, response ->
            try {
                quit()
            } finally {
                response.performQuit()
            }
        }
        return AutoCloseable {
            if (canReopen) platform.removeAppEventListener(reopen)
            if (supportsFiles) platform.setOpenFileHandler(null)
            if (supportsQuit) platform.setQuitHandler(null)
        }
    }

    fun openAudio(files: List<File>) {
        if (files.isNotEmpty()) audio.trySend(files)
    }

    suspend fun playAudio(files: List<File>): Boolean {
        return try {
            val songs = DesktopLocalMusic.scan(files).songs
            if (songs.isEmpty()) return false
            withContext(Dispatchers.IO) { Library.addLocalTracks(songs) }
            Player.playAll(songs, 0)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
