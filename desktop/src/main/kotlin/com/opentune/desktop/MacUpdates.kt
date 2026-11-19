package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.opentune.core.BuildConfig
import com.opentune.data.Http
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

internal object MacUpdates {
    private const val releases = "https://api.github.com/repos/747elias/Opentune/releases?per_page=30"
    private const val downloads = "https://github.com/747elias/Opentune/releases/download/"
    private val tagPattern = Regex("macos-v([0-9]+(?:\\.[0-9]+){1,2})")
    private val shaPattern = Regex("[0-9a-f]{64}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _available = MutableStateFlow<Installer?>(null)
    val available = _available.asStateFlow()

    data class Installer(val version: String, val name: String, val url: String, val sha256: String)

    fun start() {
        if (!DesktopWindow.isMac) return
        scope.launch {
            delay(30_000)
            while (true) {
                runCatching { checkForUpdate() }
                delay(3_600_000)
            }
        }
    }

    suspend fun checkForUpdate(): Installer? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(releases).header("Accept", "application/vnd.github+json").build()
        val latest = Http.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub answered ${response.code}." }
            findInstaller(JSONArray(response.body?.string().orEmpty()), BuildConfig.VERSION_NAME)
        }
        _available.value = latest
        latest
    }

    internal fun findInstaller(response: JSONArray, current: String): Installer? =
        (0 until response.length()).mapNotNull { index ->
            val release = response.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@mapNotNull null
            val tag = release.optString("tag_name")
            val version = tagPattern.matchEntire(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            if (compareVersion(version, current) <= 0) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (name.matches(Regex("OpenTune-[A-Za-z0-9._-]+-macos-universal\\.dmg")) &&
                    url == "$downloads$tag/$name" && digest.matches(shaPattern)) return@mapNotNull Installer(version, name, url, digest)
            }
            null
        }.maxWithOrNull { a, b -> compareVersion(a.version, b.version) }

    private fun compareVersion(a: String, b: String): Int {
        val left = a.substringBefore('-').split('.')
        val right = b.substringBefore('-').split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val order = (left.getOrNull(i)?.toIntOrNull() ?: 0).compareTo(right.getOrNull(i)?.toIntOrNull() ?: 0)
            if (order != 0) return order
        }
        return 0
    }

    suspend fun download(installer: Installer, file: File) = withContext(Dispatchers.IO) {
        val destination = file.absoluteFile.toPath()
        val staged = Files.createTempFile(destination.parent, ".opentune-update-", ".dmg")
        try {
            val request = Request.Builder().url(installer.url).build()
            val digest = MessageDigest.getInstance("SHA-256")
            val client = Http.client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "The installer download failed (${response.code})." }
                val body = checkNotNull(response.body) { "GitHub returned an empty download." }
                java.security.DigestInputStream(body.byteStream(), digest).use { input ->
                    Files.newOutputStream(staged).use { output -> input.copyTo(output) }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == installer.sha256) { "The installer checksum didn't match." }
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}

@Composable
internal fun MacUpdateSettings() {
    if (!DesktopWindow.isMac) return
    val update by MacUpdates.available.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    message = if (MacUpdates.checkForUpdate() == null) "OpenTune is up to date." else null
                } catch (error: Exception) {
                    message = error.localizedMessage
                } finally { busy = false }
            }
        }) { Text("Check for updates") }
        update?.let { installer ->
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val file = desktopFile("Save OpenTune update", installer.name)
                        if (file != null) {
                            message = "Downloading and verifying the installer…"
                            MacUpdates.download(installer, file)
                            message = "Verified installer saved. Open the DMG to install it."
                        }
                    } catch (error: Exception) {
                        message = error.localizedMessage
                    } finally { busy = false }
                }
            }) { Text("Download ${installer.version}") }
        }
    }
    message?.let { Text(it) }
}
