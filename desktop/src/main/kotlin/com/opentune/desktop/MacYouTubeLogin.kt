package com.opentune.desktop

import com.opentune.data.account.AccountStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Calls the bundled WebKit sign-in helper over a private process pipe. */
internal object MacYouTubeLogin {
    private const val SESSION_FRAME = "OpenTune-YouTube-Session-v1 "
    private const val SELF_CHECK_OK = "OpenTune login self-check: ok"
    private const val MAX_COOKIE_BYTES = 32_768
    private const val MAX_FRAME_BYTES = SESSION_FRAME.length + (MAX_COOKIE_BYTES + 2) / 3 * 4 + 1

    val available: Boolean get() = helperPath() != null

    suspend fun signIn(): String? = withTimeout(15 * 60_000L) {
        withContext(Dispatchers.IO) {
            val helper = checkNotNull(helperPath()) { "The macOS sign-in helper is missing" }
            val process = start(helper)
            try {
                val frame = async { process.inputStream.use { it.readNBytes(MAX_FRAME_BYTES + 1) } }
                while (process.isAlive) delay(100)
                val output = frame.await()
                try {
                    when (process.exitValue()) {
                        0 -> decodeFrame(output)
                        2 -> null
                        else -> error("The macOS sign-in window closed before connecting")
                    }
                } finally {
                    output.fill(0)
                }
            } finally {
                process.destroyForcibly()
            }
        }
    }

    fun selfCheck(): String {
        val helper = helperPath() ?: return "development launcher"
        val process = start(helper, "--self-check")
        try {
            check(process.waitFor(25, TimeUnit.SECONDS) && process.exitValue() == 0) { "The WebKit sign-in self-check failed" }
            check(process.inputStream.use { it.readNBytes(256) }.toString(Charsets.UTF_8).trim() == SELF_CHECK_OK)
            return "isolated WebKit session"
        } finally {
            process.destroyForcibly()
        }
    }

    internal fun decodeFrame(bytes: ByteArray): String {
        require(bytes.size <= MAX_FRAME_BYTES)
        val output = bytes.toString(Charsets.US_ASCII)
        require(output.startsWith(SESSION_FRAME) && output.endsWith('\n'))
        val decoded = Base64.getDecoder().decode(output.removePrefix(SESSION_FRAME).dropLast(1))
        try {
            require(decoded.size <= MAX_COOKIE_BYTES && decoded.all { it.toInt() in 0x20..0x7e })
            return decoded.toString(Charsets.US_ASCII).also { require(AccountStore.hasSession(it)) }
        } finally {
            decoded.fill(0)
        }
    }

    private fun start(helper: Path, vararg args: String): Process =
        ProcessBuilder(listOf(helper.toString(), *args))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .also { it.outputStream.close() }

    private fun helperPath(): Path? {
        if (!DesktopWindow.isMac) return null
        val runtimeBundle = Path.of(System.getProperty("java.home")).parent?.parent?.parent
        val executables = sequenceOf(System.getProperty("jpackage.app-path"), ProcessHandle.current().info().command().orElse(null))
            .filterNotNull()
            .mapNotNull { Path.of(it).toAbsolutePath().normalize().parent?.parent }
        return (sequenceOf(runtimeBundle).filterNotNull() + executables)
            .map { it.resolve("Helpers/OpenTuneLogin") }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}
