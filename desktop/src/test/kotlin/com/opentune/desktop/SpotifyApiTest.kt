package com.opentune.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyApiTest {
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private val trackId = "A".repeat(22)

    @Test
    fun oauthUsesPkceAndRejectsUnrelatedCallbacks() = runBlocking {
        val grants = LinkedBlockingQueue<Map<String, String>>()
        withServer({ request ->
            grants.add(request.requestBody.bufferedReader().use { it.readText() }.formParameters())
            request.reply("""{"access_token":"fixture-access","expires_in":3600,"refresh_token":"fixture-refresh"}""")
        }) { base ->
            val api = SpotifyApi(http, accounts = base.toHttpUrl())
            val client = "a".repeat(32)
            var challenge = ""
            var redirect = ""
            val token = api.signIn(client) { uri ->
                val url = uri.toString().toHttpUrl()
                assertEquals("/authorize", url.encodedPath)
                assertEquals(client, url.queryParameter("client_id"))
                assertEquals("S256", url.queryParameter("code_challenge_method"))
                challenge = requireNotNull(url.queryParameter("code_challenge"))
                redirect = requireNotNull(url.queryParameter("redirect_uri"))
                val redirectUrl = redirect.toHttpUrl()
                assertEquals("127.0.0.1", redirectUrl.host)
                assertEquals(SpotifyApi.REDIRECT_URI.toHttpUrl().encodedPath, redirectUrl.encodedPath)
                val state = requireNotNull(url.queryParameter("state"))
                fun callback(completionState: String) = http.newCall(Request.Builder().url(
                    redirectUrl.newBuilder().addQueryParameter("state", completionState).addQueryParameter("code", "fixture-code").build(),
                ).build()).execute().use { it.code }
                assertEquals(400, callback("different-state"))
                assertEquals(200, callback(state))
            }
            val grant = grants.remove()
            val verifier = requireNotNull(grant["code_verifier"])
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            assertEquals(43, verifier.length)
            assertEquals(expected, challenge)
            assertEquals("authorization_code", grant["grant_type"])
            assertEquals("fixture-code", grant["code"])
            assertEquals(redirect, grant["redirect_uri"])
            assertEquals(client, grant["client_id"])
            assertFalse(grant.containsKey("client_secret"))
            assertEquals("fixture-refresh", token.refresh)
        }
    }

    @Test
    fun spotifyPlaylistReadsAndLikesUseTheCurrentEndpoints() = runBlocking {
        val requests = LinkedBlockingQueue<Triple<String, String, String?>>()
        withServer({ request ->
            val path = request.requestURI.path
            val query = request.requestURI.rawQuery.orEmpty().formParameters()
            requests.add(Triple(request.requestMethod, path, request.requestHeaders.getFirst("Authorization")))
            when (path) {
                "/v1/playlists/$trackId/items" -> {
                    val body = if (query["offset"] == "0") {
                        """{"items":[{"item":{"id":"$trackId","name":"One"}},null,{"item":{"name":"Episode","type":"episode"}}],"next":"next-page"}"""
                    } else {
                        """{"items":[{"track":{"id":"$trackId","name":"Two"}},{"item":{"id":"$trackId","name":"Local file","is_local":true}}]}"""
                    }
                    request.reply(body)
                }
                "/v1/me/library" -> request.reply("", if (query["uris"] == "spotify:track:$trackId") 200 else 400)
                else -> request.reply("", 404)
            }
        }) { base ->
            val api = SpotifyApi(http, api = "${base}v1/".toHttpUrl())
            val tracks = api.tracks("fixture-token", trackId)
            assertEquals(listOf("One", "Two"), tracks.map { it.name })
            api.setLiked("fixture-token", trackId, true)
            api.setLiked("fixture-token", trackId, false)
            assertEquals(listOf("GET", "GET", "PUT", "DELETE"), requests.map { it.first })
            assertTrue(requests.all { it.third == "Bearer fixture-token" })
        }
    }

    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        server.createContext("/") { exchange -> exchange.use(handler) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }

    private fun String.formParameters(): Map<String, String> = split('&').mapNotNull { part ->
        part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) ->
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }
    }.toMap()

    private fun HttpExchange.reply(body: String, code: Int = 200) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    }
}
