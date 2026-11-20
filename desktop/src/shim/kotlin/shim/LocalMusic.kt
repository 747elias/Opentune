package com.opentune.data.local

/** Shared identity for tracks stored on the device. Desktop folder scanning uses the filesystem. */
object LocalMusic {
    const val PREFIX = "local:"

    fun isLocal(videoId: String) = videoId.startsWith(PREFIX)
}
