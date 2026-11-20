package com.opentune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.opentune.data.Http
import com.opentune.data.covers.AlbumCovers
import com.opentune.data.covers.AppleMotionArt
import com.opentune.data.model.Song
import com.opentune.data.settings.MotionQuality
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

/** The album's public Apple Music clip, with the static cover visible while it loads. */
@Composable
internal fun MotionCover(
    song: Song,
    durationMs: Long,
    playing: Boolean,
    quality: MotionQuality,
    unmeteredOnly: Boolean,
    shape: Shape,
    modifier: Modifier,
) {
    val frame by produceState<ImageBitmap?>(null, song.videoId, durationMs, playing, quality, unmeteredOnly) {
        value = null
        if (!playing || durationMs <= 0 || !AlbumCovers.wants(song)) return@produceState
        withContext(Dispatchers.IO) {
            if (unmeteredOnly && (!DesktopWindow.isMac || !MacMediaControls.smartNetworkAvailable())) return@withContext
            if (!Ffmpeg.available) return@withContext
            val motion = AppleMotionArt.forSong(song, durationMs) ?: return@withContext
            for (address in listOfNotNull(motion.url, motion.fallbackUrl)) {
                try {
                    val url = selectClip(address, quality) ?: continue
                    playClip(url, quality) { value = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    value = null
                }
            }
        }
    }
    Box(modifier.clip(shape)) {
        Artwork(song.thumbnailUrl, Modifier.matchParentSize(), shape)
        frame?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

private fun appleUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() && (url.host == "apple.com" || url.host.endsWith(".apple.com"))

private fun selectClip(address: String, quality: MotionQuality): String? {
    val base = address.toHttpUrlOrNull()?.takeIf(::appleUrl) ?: return null
    val playlist = Http.client.newCall(Request.Builder().url(base).build()).execute().use { response ->
        if (!response.isSuccessful || !appleUrl(response.request.url)) return null
        response.body?.byteStream()?.readNBytes(65_537)?.takeIf { it.size <= 65_536 }?.toString(Charsets.UTF_8)
            ?: return null
    }
    if (!playlist.startsWith("#EXTM3U")) return null
    val variants = playlist.lineSequence().map(String::trim).filter(String::isNotBlank).zipWithNext()
        .filter { (metadata, target) -> metadata.startsWith("#EXT-X-STREAM-INF:") && !target.startsWith('#') }
        .mapNotNull { (metadata, target) ->
            val peak = BANDWIDTH.find(metadata)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val dims = RESOLUTION.find(metadata)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull).orEmpty()
            val url = base.resolve(target)?.takeIf(::appleUrl) ?: return@mapNotNull null
            if (dims.any { it > quality.maxPx } || peak > quality.maxBitrate) null else peak to url
        }.maxByOrNull { it.first }
    return variants?.second?.toString() ?: base.toString().takeUnless { "#EXT-X-STREAM-INF:" in playlist }
}

private val BANDWIDTH = Regex("(?:[:,])BANDWIDTH=(\\d+)")
private val RESOLUTION = Regex("(?:[:,])RESOLUTION=(\\d+)x(\\d+)")

private suspend fun playClip(url: String, quality: MotionQuality, display: (ImageBitmap) -> Unit) {
    FFmpegFrameGrabber(url).use { decoder ->
        decoder.setOption("rw_timeout", "5000000")
        decoder.setOption("protocol_whitelist", "https,tls,tcp")
        decoder.pixelFormat = AV_PIX_FMT_BGRA
        decoder.start()
        val sourceWidth = decoder.imageWidth.coerceAtLeast(1)
        val sourceHeight = decoder.imageHeight.coerceAtLeast(1)
        val maxSide = when (quality) { MotionQuality.LOW -> 400; MotionQuality.MEDIUM -> 600; MotionQuality.HIGH -> 800 }
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(sourceWidth, sourceHeight))
        decoder.imageWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        decoder.imageHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)

        while (true) {
            var first = -1L
            var last = -1L
            val start = System.nanoTime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = decoder.grabImage() ?: break
                if (first < 0) first = frame.timestamp
                val elapsed = (frame.timestamp - first).coerceAtLeast(0)
                if (last >= 0 && elapsed - last < 65_000) continue
                last = elapsed
                val waitMs = elapsed / 1000 - (System.nanoTime() - start) / 1_000_000
                if (waitMs > 0) delay(waitMs.coerceAtMost(1000))
                val pixels = frame.image.first() as ByteBuffer
                val stride = frame.imageStride
                val bytes = ByteArray(stride * frame.imageHeight)
                pixels.duplicate().apply { rewind() }.get(bytes)
                val bitmap = Bitmap()
                val info = ImageInfo.makeN32Premul(frame.imageWidth, frame.imageHeight)
                check(bitmap.installPixels(info, bytes, stride))
                display(bitmap.asComposeImageBitmap())
            }
            if (first < 0 || last <= 0) return
            decoder.setVideoTimestamp(0)
        }
    }
}
