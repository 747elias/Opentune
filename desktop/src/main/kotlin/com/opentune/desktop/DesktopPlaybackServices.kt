package com.opentune.desktop

import com.opentune.data.isYouTubeId
import com.opentune.data.settings.AppSettings
import com.opentune.data.sponsorblock.SponsorBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal object DesktopPlaybackServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            combine(Player.queue, Player.index, AppSettings.playback) { queue, index, settings ->
                queue.getOrNull(index)?.videoId?.takeIf(::isYouTubeId) to
                    settings.sponsorBlockCategories.takeIf { settings.sponsorBlock }.orEmpty()
            }.distinctUntilChanged().collectLatest { (id, categories) ->
                if (id == null || categories.isEmpty()) return@collectLatest
                val segments = SponsorBlock.segments(id, categories)
                val skipped = mutableSetOf<String>()
                while (Player.current?.videoId == id && skipped.size < segments.size) {
                    delay(if (Player.playing.value) 180 else 600)
                    if (Player.current?.videoId != id || !Player.playing.value || DesktopRooms.isGuest) continue
                    val at = Player.positionMs()
                    val duration = Player.durationMs()
                    val segment = segments.firstOrNull {
                        it.uuid !in skipped && at >= it.startMs && at < it.endMs - 150 && SponsorBlock.fits(it, duration)
                    } ?: continue
                    skipped += segment.uuid
                    if (duration > 0 && segment.endMs >= duration - 150 && Player.index.value < Player.queue.value.lastIndex) {
                        Player.next()
                    } else {
                        Player.seekTo(segment.endMs.coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                    }
                }
            }
        }
    }
}
