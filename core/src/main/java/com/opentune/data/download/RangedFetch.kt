package com.opentune.data.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal class SmartDownloadDeferred : IOException("Smart downloads wait for Wi-Fi, battery and free space")

/**
 * A file fetched in [CHUNK]-sized ranges, a few at a time. One open-ended
 * request is slowed by googlevideo to about the speed the song plays at;
 * ranges the size the player asks for come at the connection's full speed.
 * Stream URLs are probed with a range this size (see the player's stream resolver), so
 * they serve them.
 */
internal object RangedFetch {
    /** The range the player fetches with. */
    const val CHUNK = 2L * 1024 * 1024
    private const val PARALLEL = 3
    private const val ATTEMPTS = 3

    suspend fun fetch(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        part: File,
        parallel: Int = PARALLEL,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Ranges land out of order, so a partial file isn't a prefix to resume from.
        part.delete()
        val total = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
            ?: sizeFrom(client, url, headers)
            // A server that doesn't serve ranges (many add-ons' file hosts) sends the file whole.
            ?: return@withContext fetchWhole(client, url, headers, part, maxBytes, onProgress)
        require(total in 1..maxBytes) { "Audio file exceeds the download size limit." }
        val done = AtomicLong(0)
        val reported = AtomicLong(0)
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val starts = (0 until total step CHUNK).toList()
            val next = AtomicInteger(0)
            coroutineScope {
                repeat(minOf(parallel, starts.size)) {
                    launch {
                        while (true) {
                            val start = starts.getOrNull(next.getAndIncrement()) ?: break
                            val end = minOf(start + CHUNK, total) - 1
                            fetchRange(client, url, headers, start, end, total, file) { n ->
                                val now = done.addAndGet(n)
                                val last = reported.get()
                                if (now - last > 256 * 1024 && reported.compareAndSet(last, now)) onProgress(now.toFloat() / total)
                            }
                        }
                    }
                }
            }
        }
        if (part.length() != total) throw IOException("Download came out the wrong size")
    }

    /**
     * The file's size, from the total in a one-byte range's Content-Range;
     * null when the server doesn't serve ranges (it answers with the whole
     * file, or without a total).
     */
    private fun sizeFrom(client: OkHttpClient, url: String, headers: Map<String, String>): Long? {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Accept-Encoding", "identity")
            header("Range", "bytes=0-0")
        }.build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server answered ${r.code}")
            if (r.code != 206) return null
            return r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    /** The whole file in one request, from the start, tried a few times. */
    private suspend fun fetchWhole(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        part: File,
        maxBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            try {
                runInterruptible {
                    val request = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        header("Accept-Encoding", "identity")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        if (type.contains("text/") || type.contains("json") || type.contains("html")) {
                            throw IOException("Server returned a page instead of audio")
                        }
                        val body = response.body ?: throw IOException("Empty response")
                        val length = body.contentLength().takeIf { it > 0 }
                        if (length != null && length > maxBytes) throw IOException("Audio file exceeds the download size limit.")
                        var got = 0L
                        var reported = 0L
                        part.outputStream().buffered().use { out ->
                            val input = body.byteStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                got += n
                                if (got > maxBytes) throw IOException("Audio file exceeds the download size limit.")
                                out.write(buf, 0, n)
                                if (got - reported > 256 * 1024) {
                                    reported = got
                                    // Unknown-size streams still need policy/cancellation checks.
                                    onProgress(if (length != null) got.toFloat() / length else 0f)
                                }
                            }
                        }
                        if (got == 0L || (length != null && got != length)) throw IOException("Download came out the wrong size")
                    }
                }
                return
            } catch (e: SmartDownloadDeferred) {
                part.delete()
                throw e
            } catch (e: IOException) {
                part.delete()
                if (++attempt >= ATTEMPTS) throw e
                delay(500L * attempt)
            }
        }
    }

    /** Bytes [start]..[end] into [file] at their place, tried a few times. */
    private suspend fun fetchRange(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        total: Long,
        file: RandomAccessFile,
        onBytes: (Long) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            var got = 0L
            try {
                runInterruptible {
                    val request = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        header("Accept-Encoding", "identity")
                        header("Range", "bytes=$start-$end")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 206) throw IOException("Server answered ${response.code}")
                        if (response.header("Content-Range") != "bytes $start-$end/$total") {
                            throw IOException("Server returned a different audio range")
                        }
                        val type = response.header("Content-Type").orEmpty().lowercase()
                        if (type.contains("text/") || type.contains("json") || type.contains("html")) {
                            throw IOException("Server returned a page instead of audio")
                        }
                        val input = response.body?.byteStream() ?: throw IOException("Empty response")
                        val buf = ByteArray(64 * 1024)
                        var at = start
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (at + n > end + 1) throw IOException("Audio range is longer than requested")
                            synchronized(file) { file.seek(at); file.write(buf, 0, n) }
                            at += n
                            got += n
                            onBytes(n.toLong())
                        }
                        if (at != end + 1) throw IOException("Range ended early")
                    }
                }
                return
            } catch (e: SmartDownloadDeferred) {
                throw e
            } catch (e: IOException) {
                // Take back what this try counted, so progress doesn't run past the end.
                if (got > 0) onBytes(-got)
                if (++attempt >= ATTEMPTS) throw e
                delay(500L * attempt)
            }
        }
    }
}
