package com.opentune.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedMediaTest {
    @Test fun onlyUsesPlayableDirectAudioFromTheSignedPlayer() {
        val response = """{
            "playabilityStatus":{"status":"OK"},
            "streamingData":{"adaptiveFormats":[
                {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"url":"https://r1.googlevideo.com/videoplayback?fixture=1"},
                {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"url":"https://r1.googlevideo.com/videoplayback?fixture=2"},
                {"mimeType":"audio/mp4","bitrate":256000,"url":"https://r1.googlevideo.com.example.test/videoplayback"},
                {"mimeType":"audio/mp4","bitrate":256000,"url":"http://r1.googlevideo.com/videoplayback"},
                {"mimeType":"video/mp4","bitrate":256000,"url":"https://r1.googlevideo.com/videoplayback"}
            ]}
        }"""
        val headers = mapOf("User-Agent" to "fixture")
        val formats = Streams.authorizedFormats(Json.parseToJsonElement(response).jsonObject, headers, null)
        assertEquals(listOf(128), formats.getValue(Streams.Kind.AAC).map { it.kbps })
        assertEquals(listOf(160), formats.getValue(Streams.Kind.OPUS).map { it.kbps })
        assertEquals(headers, formats.getValue(Streams.Kind.AAC).single().headers)
        assertTrue(Streams.authorizedFormats(Json.parseToJsonElement(response.replace("\"OK\"", "\"LOGIN_REQUIRED\"")).jsonObject,
            headers, null).isEmpty())
    }
}
