// Downloads as the shared lyrics code reaches for them. Files in this folder
// stand in for parts of core the desktop does not compile. They sit here,
// not under their packages' folders, so build.gradle.kts's include patterns
// cannot pick up core's real ones.
package com.opentune.data.download

import com.opentune.desktop.OfflineDownloads

object Downloads {
    fun lyricsFileFor(videoId: String) = OfflineDownloads.lyricsFileFor(videoId)
}
