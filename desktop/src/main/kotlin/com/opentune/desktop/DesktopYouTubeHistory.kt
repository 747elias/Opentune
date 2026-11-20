package com.opentune.desktop

import com.opentune.data.history.ListeningHistoryTarget
import com.opentune.data.history.YouTubeHistory
import com.opentune.data.history.YouTubeHistorySession
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Count audible time independently of the playhead, so seeking cannot create a listen. */
internal object DesktopYouTubeHistory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null

    private class Listen(val id: String, val index: Int, val revision: Int, val history: YouTubeHistorySession) {
        var heard = 0L
        var duration = 0L
        var position = 0L
        var playing = false
        var lastReport = 0L
        var report: Job? = null
    }

    fun start() {
        if (monitor?.isActive == true) return
        YouTubeHistory.init(Streams::signatureTimestamp)
        monitor = scope.launch {
            var listen: Listen? = null
            var previous = System.nanoTime()
            while (isActive) {
                delay(1_000)
                val now = System.nanoTime()
                val elapsed = ((now - previous) / 1_000_000).coerceIn(0L, 1_500L)
                previous = now
                val settings = AppSettings.playback.value
                val song = Player.current
                val revision = DesktopAccount.revision.value
                if (!DesktopAccount.signedIn.value || settings.pauseListeningHistory ||
                    settings.listeningHistoryTarget != ListeningHistoryTarget.YOUTUBE_MUSIC
                ) {
                    listen = null
                    continue
                }
                val prior = listen
                val index = Player.index.value
                val playing = Player.playing.value
                val position = Player.positionMs()
                val restarted = prior != null && playing && position < 1_500 && prior.position > 5_000
                if (prior == null || prior.id != song?.videoId || prior.index != index || prior.revision != revision || restarted) {
                    if (prior != null && prior.revision == revision) report(prior, final = true)
                    listen = song?.let {
                        val session = YouTubeHistory.session(it.videoId) {
                            DesktopAccount.revision.value == revision
                        } ?: return@let null
                        Listen(it.videoId, index, revision, session)
                    }
                } else if (playing && prior.playing) {
                    prior.heard += elapsed
                }
                listen?.let { current ->
                    current.position = position
                    current.playing = playing
                    current.duration = Player.durationMs().takeIf { it > 0 } ?: current.duration
                    val threshold = minOf(30_000L, current.duration.takeIf { it > 0 }?.div(2) ?: 30_000L)
                    if (current.heard >= threshold && current.heard - current.lastReport >= 5_000 && current.report?.isActive != true) {
                        report(current)
                    }
                }
            }
        }
    }

    private fun report(listen: Listen, final: Boolean = false) {
        val heard = listen.heard
        val duration = listen.duration.takeIf { it > 0 }
        listen.lastReport = heard
        listen.report = scope.launch {
            // Keep the account selection fixed through the request even on a concurrent sign-out.
            DesktopAccount.reportHistory { listen.history.heard(heard, duration, final) }
        }
    }
}
