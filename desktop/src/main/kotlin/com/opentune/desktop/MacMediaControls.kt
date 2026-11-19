package com.opentune.desktop

import com.opentune.data.settings.AppSettings
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object MacMediaControls {
    interface Bridge : Library {
        fun opentune_install_media_commands(callback: MediaCommand)
        fun opentune_set_now_playing(title: String?, artist: String?, album: String?, duration: Double, elapsed: Double, rate: Double)
    }

    fun interface MediaCommand : Callback {
        fun handle(command: Int, seconds: Double)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = MediaCommand { command, seconds ->
        scope.launch {
            when (command) {
                0 -> Player.setPlaying(true)
                1 -> Player.setPlaying(false)
                2 -> Player.toggle()
                3 -> Player.next()
                4 -> Player.previous()
                5 -> if (seconds.isFinite() && seconds >= 0) Player.seekTo((seconds * 1000).toLong())
            }
        }
    }

    private val path: File? get() = File(System.getProperty("java.home")).parentFile?.parentFile?.parentFile
        ?.resolve("Frameworks/libOpenTuneMedia.dylib")?.takeIf(File::isFile)

    private val bridge by lazy { path?.let { Native.load(it.absolutePath, Bridge::class.java) } }

    fun checkPackaged() = if (path == null) "development launcher" else {
        checkNotNull(bridge)
        "loaded"
    }

    fun start() {
        if (!DesktopWindow.isMac) return
        val active = runCatching { bridge }.getOrNull() ?: return
        active.opentune_install_media_commands(commands)
        scope.launch {
            while (true) {
                val song = Player.current
                active.opentune_set_now_playing(
                    song?.title, song?.artist, song?.albumName, Player.durationMs() / 1000.0, Player.positionMs() / 1000.0,
                    if (Player.playing.value) AppSettings.sound.value.speed.toDouble() else 0.0,
                )
                delay(700)
            }
        }
    }
}
