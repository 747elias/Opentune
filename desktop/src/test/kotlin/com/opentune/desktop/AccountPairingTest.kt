package com.opentune.desktop

import android.content.Context
import com.opentune.data.account.PairingOffer
import com.opentune.data.account.PairingReceiver
import com.opentune.data.account.PairingSender
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class AccountPairingTest {
    @Test
    fun nativeSignInAcceptsOnlyAFramedSessionWithoutControlCharacters() {
        fun frame(value: String): ByteArray =
            ("OpenTune-YouTube-Session-v1 " + Base64.getEncoder().encodeToString(value.toByteArray()) + "\n").toByteArray()

        val session = "SAPISID=sign-in-fixture; SID=not-a-credential"
        assertEquals(session, MacYouTubeLogin.decodeFrame(frame(session)))
        for (invalid in listOf("SAPISID", "SAPISID=", "SID=sign-in-fixture", "$session\r\nOther-Header: fixture")) {
            assertThrows(IllegalArgumentException::class.java) { MacYouTubeLogin.decodeFrame(frame(invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { MacYouTubeLogin.decodeFrame(session.toByteArray()) }
    }

    @Test
    fun androidSenderCanPairOnceThroughTheDesktopListener() = runBlocking {
        val receiver = runCatching { PairingReceiver() }.getOrNull()
        assumeNotNull(receiver)
        checkNotNull(receiver).use { listening ->
            val offer = PairingOffer.parse(listening.offer.link.substringAfter('#'))
            val cookie = "SAPISID=pairing-fixture; SID=not-a-credential"
            withTimeout(12_000) {
                val accepted = async(Dispatchers.IO) {
                    var received: String? = null
                    listening.receive { received = it; true }
                    received
                }
                withContext(Dispatchers.IO) {
                    val invalid = PairingOffer.create(offer.host, offer.port)
                    assertTrue(runCatching { PairingSender.send(invalid, cookie) }.isFailure)
                    PairingSender.send(offer, cookie)
                    assertTrue(runCatching { PairingSender.send(offer, cookie) }.isFailure)
                }
                assertEquals(cookie, accepted.await())
            }
        }
    }

    @Test
    fun accountAndListenBrainzCredentialsUsePrivateFiles() {
        val directory = Files.createTempDirectory("opentune-account-test").toFile()
        try {
            for (name in listOf("account", "listenbrainz")) {
                val context = Context(directory)
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                assertTrue(prefs.edit().putString("fixture", "credential-fixture").commit())
                val path = directory.toPath().resolve("prefs/$name.properties")
                if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
                }
                val restored = Context(directory).getSharedPreferences(name, Context.MODE_PRIVATE)
                assertEquals("credential-fixture", restored.getString("fixture", ""))
                assertTrue(restored.edit().remove("fixture").commit())
                assertEquals("", Context(directory).getSharedPreferences(name, Context.MODE_PRIVATE).getString("fixture", ""))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
