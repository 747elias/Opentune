package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.LoudnessStore
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.PlayerClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Where a song's audio is, found with NewPipe as the phone app's last
 * fallback does: AAC in MP4, which JavaFX plays on every system, and Opus,
 * YouTube's best, which FFmpeg plays.
 */
object Streams {
    data class Stream(
        val url: String,
        val kbps: Int,
        val mimeType: String,
        val loudnessDb: Double? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    /** Which of a song's streams: AAC for JavaFX, or Opus (YouTube's best) for FFmpeg. */
    enum class Kind(val extension: String) { AAC("m4a"), OPUS("webm") }

    /** YouTube's addresses last about six hours; reused for well under that. */
    private const val KEEP_MS = 60 * 60 * 1000L
    private data class Cached(val at: Long, val variants: Map<Kind, List<Stream>>)
    private val found = ConcurrentHashMap<String, Cached>()
    private data class AccountStreams(val revision: Int, val cache: Cached)
    private val signed = ConcurrentHashMap<String, AccountStreams>()
    private val signedGate = Mutex()

    /** One extraction at a time: they share NewPipe's state and slow each other right down. */
    private val gate = Mutex()

    private val init by lazy { NewPipe.init(OkHttpDownloader) }

    @Volatile private var cachedSignatureTimestamp: Int? = null

    internal suspend fun signatureTimestamp(videoId: String): Int? = gate.withLock {
        cachedSignatureTimestamp ?: withContext(Dispatchers.IO) {
            init
            runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        }?.also { cachedSignatureTimestamp = it }
    }
    private val measures by lazy { LoudnessStore.also { it.init(appContext) } }
    private val lastLoudnessTry = ConcurrentHashMap<String, Long>()

    fun loudnessDbFor(videoId: String): Double? = measures.get(videoId)

    /** Some NewPipe clients omit the measurement. The Music player endpoint may have it. */
    internal suspend fun loadLoudness(videoId: String) {
        if (loudnessDbFor(videoId) != null) return
        val now = System.currentTimeMillis()
        val previous = lastLoudnessTry.putIfAbsent(videoId, now)
        if (previous != null && (now - previous < KEEP_MS || !lastLoudnessTry.replace(videoId, previous, now))) return
        if (lastLoudnessTry.size > 512) lastLoudnessTry.entries.removeIf { now - it.value > KEEP_MS }
        try {
            rememberLoudness(Innertube.player(videoId, PlayerClient.ANDROID_MUSIC))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unmeasured tracks play at their original level.
        }
    }

    private fun rememberLoudness(response: JsonObject) {
        val id = response["videoDetails"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull ?: return
        val db = response["playerConfig"]?.jsonObject?.get("audioConfig")?.jsonObject?.get("loudnessDb")
            ?.jsonPrimitive?.doubleOrNull
            ?: response["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray?.firstNotNullOfOrNull { element ->
                val format = element.jsonObject
                if (format["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/") == true)
                    format["loudnessDb"]?.jsonPrimitive?.doubleOrNull
                else null
            }
        if (db?.isFinite() == true) measures.put(id, db)
    }

    suspend fun resolve(videoId: String, fresh: Boolean = false, kind: Kind = Kind.AAC, maxKbps: Int = Int.MAX_VALUE): Stream {
        val candidates = variants(videoId, fresh, kind)
        return choose(candidates[kind].orEmpty(), maxKbps) ?: error("No ${kind.name} audio for $videoId")
    }

    /** At most one extraction for the complete list of download candidates. */
    internal suspend fun downloadChoices(videoId: String, maxKbps: Int, preferred: Kind, fresh: Boolean): List<Pair<Kind, Stream>> {
        val candidates = variants(videoId, fresh, preferred)
        val kinds = listOf(preferred) + Kind.entries.filter { it != preferred }
        return kinds.mapNotNull { kind -> choose(candidates[kind].orEmpty(), maxKbps)?.let { kind to it } }
            .sortedWith(compareBy({ it.second.kbps !in 1..maxKbps }, { if (it.second.kbps > maxKbps) it.second.kbps else 0 }))
    }

    private suspend fun variants(videoId: String, fresh: Boolean, preferred: Kind): Map<Kind, List<Stream>> {
        if (!fresh) {
            val public = found[videoId]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.variants.orEmpty()
            if (public[preferred].orEmpty().isNotEmpty()) return public
            val active = signedCache(videoId)?.variants.orEmpty()
            if (active[preferred].orEmpty().isNotEmpty()) {
                return Kind.entries.associateWith { kind -> active[kind].orEmpty().ifEmpty { public[kind].orEmpty() } }
            }
        }
        val guest = try {
            Result.success(publicVariants(videoId, fresh).variants)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val public = guest.getOrDefault(emptyMap())
        if (!fresh && public[preferred].orEmpty().isNotEmpty()) return public
        val authorized = signedVariants(videoId, fresh)
        if (authorized.isEmpty()) return guest.getOrThrow()
        return Kind.entries.associateWith { kind -> authorized[kind].orEmpty().ifEmpty { public[kind].orEmpty() } }
    }

    private suspend fun signedVariants(videoId: String, fresh: Boolean): Map<Kind, List<Stream>> = signedGate.withLock {
        if (!DesktopAccount.signedIn.value) return@withLock emptyMap()
        val revision = DesktopAccount.revision.value
        val now = System.currentTimeMillis()
        if (!fresh) signedCache(videoId)?.let { return@withLock it.variants }
        signed.entries.removeIf { it.value.revision != revision || now - it.value.cache.at >= KEEP_MS }
        if (signed.size >= 256) signed.clear()

        val result = withContext(Dispatchers.IO) {
            try {
                Innertube.ensureVisitorData()
                // Use the Android app's existing signed-in player route. Only consume media
                // where YouTube has returned a playable response for this listener.
                val player = PlayerClient.ANDROID_MUSIC
                val response = Innertube.player(videoId, player, authenticated = true)
                rememberLoudness(response)
                authorizedFormats(response, player.mediaHeaders(), loudnessDbFor(videoId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
        }
        if (DesktopAccount.revision.value != revision) return@withLock emptyMap()
        if (result.values.any { it.isNotEmpty() }) signed[videoId] = AccountStreams(revision, Cached(now, result))
        result.filterValues { it.isNotEmpty() }
    }

    private fun signedCache(videoId: String): Cached? = signed[videoId]?.takeIf {
        DesktopAccount.signedIn.value && it.revision == DesktopAccount.revision.value &&
            System.currentTimeMillis() - it.cache.at < KEEP_MS
    }?.cache

    internal fun authorizedFormats(response: JsonObject, headers: Map<String, String>, loudnessDb: Double?): Map<Kind, List<Stream>> {
        val playability = response["playabilityStatus"] as? JsonObject
        if ((playability?.get("status") as? JsonPrimitive)?.contentOrNull != "OK") return emptyMap()
        val data = response["streamingData"] as? JsonObject
        val formats = data?.get("adaptiveFormats") as? JsonArray ?: return emptyMap()
        val audio = formats.mapNotNull { element ->
            val format = element as? JsonObject ?: return@mapNotNull null
            val mime = (format["mimeType"] as? JsonPrimitive)?.contentOrNull?.substringBefore(';') ?: return@mapNotNull null
            if (mime != "audio/mp4" && mime != "audio/webm") return@mapNotNull null
            val url = (format["url"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.port == 443 && it.host.endsWith(".googlevideo.com") &&
                    it.username.isEmpty() && it.password.isEmpty() } ?: return@mapNotNull null
            val kbps = (format["bitrate"] as? JsonPrimitive)?.intOrNull?.div(1000)
                ?.takeIf { it > 0 } ?: return@mapNotNull null
            Stream(url.toString(), kbps, mime, loudnessDb, headers)
        }
        return Kind.entries.associateWith { kind ->
            val mime = if (kind == Kind.AAC) "audio/mp4" else "audio/webm"
            audio.filter { it.mimeType == mime }.sortedByDescending { it.kbps }
        }
    }

    private suspend fun publicVariants(videoId: String, fresh: Boolean): Cached {
        fun current() = found[videoId]?.takeIf { !fresh && System.currentTimeMillis() - it.at < KEEP_MS }
        current()?.let { return it }
        return gate.withLock {
            current()?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                init
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                val audioStreams = extractor.audioStreams
                val loudnessDb = loudnessDbFor(videoId)
                val variants = Kind.entries.associateWith { kind ->
                    candidates(audioStreams, kind).map { s ->
                        Stream(s.content, s.averageBitrate, s.format?.mimeType ?: "audio/mp4", loudnessDb)
                    }
                }
                Cached(System.currentTimeMillis(), variants).also { found[videoId] = it }
            }
        }
    }

    /** Plain addresses only, ordered from highest to lowest bitrate. */
    private fun candidates(streams: List<AudioStream>, kind: Kind): List<AudioStream> = streams
        .filter { !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .filter { if (kind == Kind.AAC) it.format?.mimeType == "audio/mp4" else it.format?.mimeType == "audio/webm" }
        .sortedByDescending { it.averageBitrate }

    internal fun pick(streams: List<AudioStream>, kind: Kind = Kind.AAC): AudioStream? = candidates(streams, kind).firstOrNull()

    private fun choose(streams: List<Stream>, maxKbps: Int): Stream? = streams.firstOrNull { it.kbps in 1..maxKbps }
        ?: streams.lastOrNull { it.kbps > 0 } ?: streams.firstOrNull()

    /** The stream already found for [videoId], for saying what's playing. */
    fun known(videoId: String, kind: Kind = Kind.AAC, maxKbps: Int = Int.MAX_VALUE): Stream? =
        signedCache(videoId)?.variants?.get(kind)?.let { choose(it, maxKbps) }
            ?: found[videoId]?.variants?.get(kind)?.let { choose(it, maxKbps) }

    /** Forgets an address the server refused, so the next try finds a new one. */
    fun forget(videoId: String) {
        found.remove(videoId)
        signed.remove(videoId)
    }

    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    /** Well-formed, empty, and over NewPipe's fifty-character floor. */
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    private val client = Http.client.newBuilder()
        .connectionPool(okhttp3.ConnectionPool())
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private object OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // "What plays next" is fetched by NewPipe and never read here, and
            // it's the one request that can hang the whole extraction.
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) hasUserAgent = true
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }
            val response = client.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return response.use { r ->
                val body = r.body?.string()
                if (r.isSuccessful && body != null && r.request.url.encodedPath == "/youtubei/v1/player") {
                    runCatching { rememberLoudness(Json.parseToJsonElement(body).jsonObject) }
                }
                Response(r.code, r.message, r.headers.toMultimap(), body, r.request.url.toString())
            }
        }
    }
}
