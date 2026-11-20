package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.spotify.Spotify
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Spotify's public Web API, using OAuth authorization code with PKCE. */
internal class SpotifyApi(
    private val http: OkHttpClient = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build(),
    private val accounts: HttpUrl = "https://accounts.spotify.com/".toHttpUrl(),
    private val api: HttpUrl = "https://api.spotify.com/v1/".toHttpUrl(),
) {
    @Serializable
    data class Token(
        @SerialName("access_token") val access: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("refresh_token") val refresh: String? = null,
    )

    @Serializable
    data class Profile(val id: String, @SerialName("display_name") val name: String? = null)

    @Serializable
    data class Playlist(val id: String, val name: String)

    @Serializable
    data class Artist(val name: String)

    @Serializable
    data class Album(val name: String? = null)

    @Serializable
    data class Track(
        val id: String? = null,
        val name: String = "",
        val artists: List<Artist> = emptyList(),
        val album: Album? = null,
        @SerialName("duration_ms") val durationMs: Long = 0,
        @SerialName("is_local") val local: Boolean = false,
        val type: String = "track",
    ) {
        val importable: Boolean get() = !local && type == "track" && name.isNotBlank() && validItemId(id)
        fun metadata() = Spotify.Track(name, artists.map { it.name }, album?.name, durationMs)
    }

    @Serializable
    private data class Entry(
        val item: Track? = null,
        val track: Track? = null,
        @SerialName("is_local") val local: Boolean = false,
    ) {
        fun playable() = (item ?: track)?.takeIf { !local && it.importable }
    }

    @Serializable
    private data class Page<T>(val items: List<T?> = emptyList(), val next: String? = null)

    class Problem(val status: Int, message: String) : IOException(message)

    suspend fun signIn(clientId: String, openBrowser: (URI) -> Unit): Token {
        require(validClientId(clientId)) { "Enter the 32-character Spotify Client ID." }
        val verifier = randomToken()
        val state = randomToken()
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val callback = kotlinx.coroutines.CompletableDeferred<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        val redirect = "http://127.0.0.1:${server.address.port}$CALLBACK"
        try {
            server.createContext(CALLBACK) { exchange -> handleCallback(exchange, state, callback) }
            server.start()
            val login = accounts.newBuilder().addPathSegment("authorize")
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirect)
                .addQueryParameter("scope", SCOPES)
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge)
                .build()
            openBrowser(login.toUri())
            val code = withTimeoutOrNull(5 * 60_000L) { callback.await() }
                ?: throw IOException("Spotify sign-in timed out. Try again.")
            return token(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
                .add("redirect_uri", redirect).add("code", code).add("code_verifier", verifier).build())
        } finally {
            server.stop(0)
        }
    }

    suspend fun refresh(clientId: String, refresh: String): Token =
        token(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refresh).build())

    suspend fun profile(access: String): Profile = json.decodeFromString(get(access, "me"))

    suspend fun playlists(access: String): List<Playlist> = pages<Playlist>(access, "me/playlists").filter { validItemId(it.id) }

    suspend fun liked(access: String): List<Track> = pages<Entry>(access, "me/tracks").mapNotNull(Entry::playable)

    suspend fun tracks(access: String, playlist: String): List<Track> {
        require(validItemId(playlist))
        val entries = try {
            pages<Entry>(access, "playlists/$playlist/items")
        } catch (error: Problem) {
            if (error.status != 404) throw error
            // The older route remains available to apps with extended quota.
            pages<Entry>(access, "playlists/$playlist/tracks")
        }
        return entries.mapNotNull(Entry::playable)
    }

    suspend fun setLiked(access: String, id: String, liked: Boolean) {
        require(validItemId(id))
        val url = address("me/library", mapOf("uris" to "spotify:track:$id"))
        val request = Request.Builder().url(url).header("Authorization", "Bearer $access")
        if (liked) request.put(ByteArray(0).toRequestBody()) else request.delete()
        execute(request.build())
    }

    private suspend inline fun <reified T> pages(access: String, path: String): List<T> = buildList {
        var offset = 0
        do {
            val page = json.decodeFromString<Page<T>>(get(access, path, mapOf("limit" to "50", "offset" to offset.toString())))
            addAll(page.items.filterNotNull())
            if (page.items.isEmpty() || page.next.isNullOrBlank()) break
            offset += page.items.size
        } while (offset <= 100_000)
    }

    private suspend fun token(form: FormBody): Token {
        val url = accounts.newBuilder().addPathSegment("api").addPathSegment("token").build()
        return json.decodeFromString(execute(Request.Builder().url(url).post(form).build()))
    }

    private fun address(path: String, query: Map<String, String>): HttpUrl =
        api.newBuilder().addPathSegments(path).apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }.build()

    private suspend fun get(access: String, path: String, query: Map<String, String> = emptyMap()): String =
        execute(Request.Builder().url(address(path, query)).header("Authorization", "Bearer $access").build())

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(IOException("Couldn't reach Spotify. Try again."))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) {
                            val retry = it.header("Retry-After")?.toLongOrNull()
                            val problem = when (it.code) {
                                400 -> "Spotify rejected this request. Check the app settings or sign in again."
                                401 -> "Spotify has signed you out. Reconnect your account."
                                403 -> "Spotify declined access. Check the app's user allowlist, Premium subscription and playlist permissions."
                                404 -> "Spotify couldn't find this playlist or track."
                                429 -> retry?.let { delay -> "Spotify is limiting requests. Try again in $delay seconds." }
                                    ?: "Spotify is limiting requests. Try again shortly."
                                else -> "Spotify couldn't complete this request (HTTP ${it.code})."
                            }
                            throw Problem(it.code, problem)
                        }
                        continuation.resume(it.body?.string().orEmpty())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun handleCallback(exchange: HttpExchange, state: String, callback: kotlinx.coroutines.CompletableDeferred<String>) {
        exchange.use {
            val request = exchange.requestURI
            val query = REDIRECT_URI.toHttpUrl().newBuilder().encodedQuery(request.rawQuery).build()
            val got = query.queryParameterValues("state").singleOrNull()
            val valid = exchange.requestMethod == "GET" && request.rawPath == CALLBACK &&
                got != null && MessageDigest.isEqual(state.toByteArray(), got.toByteArray())
            val code = query.queryParameterValues("code").singleOrNull()?.takeIf { it.isNotBlank() }
            val status: Int
            val message: String
            if (!valid) {
                status = 400
                message = "Invalid OpenTune sign-in request."
            } else if (code == null) {
                status = 400
                message = "Spotify sign-in was declined. Return to OpenTune to try again."
            } else {
                status = 200
                message = "Spotify sign-in received. Return to OpenTune."
            }
            val body = message.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
            try {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                if (valid && code != null) callback.complete(code)
                else if (valid) callback.completeExceptionally(IOException("Spotify sign-in was declined."))
            }
        }
    }

    companion object {
        private const val CALLBACK = "/opentune/spotify"
        const val REDIRECT_URI = "http://127.0.0.1$CALLBACK"
        private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read user-library-modify user-read-private"
        private val rng = SecureRandom()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val itemId = Regex("[A-Za-z0-9]{22}")
        private val clientId = Regex("[A-Fa-f0-9]{32}")

        fun validClientId(id: String) = clientId.matches(id)
        private fun validItemId(id: String?) = id != null && itemId.matches(id)
        private fun randomToken() = encode(ByteArray(32).also(rng::nextBytes))
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
