package com.opentune.desktop

import com.opentune.data.audiobooks.Audiobooks
import com.opentune.data.library.ResumePoints
import com.opentune.data.model.Song
import com.opentune.data.podcasts.Podcasts
import com.opentune.data.radio.Radio

internal object DesktopListeningProgress {
    fun save(song: Song, positionMs: Long, durationMs: Long) {
        val videoId = song.videoId
        if (positionMs < 0 || durationMs <= 0 || Radio.isRadio(videoId)) return
        when {
            Audiobooks.isChapter(videoId) -> Audiobooks.save(videoId, positionMs, song.albumName.orEmpty(), song.artist)
            Podcasts.isEpisode(videoId) -> Podcasts.savePosition(videoId, positionMs, durationMs)
            else -> ResumePoints.save(videoId, positionMs, durationMs)
        }
    }

    fun finish(videoId: String) {
        if (Podcasts.isEpisode(videoId)) Podcasts.markPlayed(videoId, played = true)
        else ResumePoints.forget(videoId)
    }
}
