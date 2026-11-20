package com.opentune.desktop

import com.opentune.data.download.smartDownloadAllowed
import com.opentune.data.download.smartDownloadCandidates
import com.opentune.data.download.mixStarts
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Refresh the set while the app is running, including while its main window is closed. */
internal object DesktopSmartDownloads {
    private const val RETRY_MS = 30_000L
    private const val REFRESH_MS = 12 * 60 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _waiting = MutableStateFlow(false)
    val waiting = _waiting.asStateFlow()

    private data class Preferences(val enabled: Boolean, val limit: Int, val battery: Int, val mixes: Boolean, val hideExplicit: Boolean)

    fun start() {
        if (!DesktopWindow.isMac) return
        scope.launch {
            AppSettings.library.map {
                Preferences(it.smartDownloads, it.smartDownloadLimit, it.smartDownloadMinBattery, it.smartDownloadMixes, it.hideExplicit)
            }.distinctUntilChanged().collectLatest { preferences ->
                OfflineDownloads.trimSmart(preferences.enabled, preferences.limit)
                _waiting.value = false
                if (!preferences.enabled) return@collectLatest
                var nextRefresh = 0L
                while (isActive) {
                    val eligible = allowed()
                    _waiting.value = !eligible
                    if (eligible) {
                        val now = System.currentTimeMillis()
                        val remote = DesktopAccount.library.value
                        if (now >= nextRefresh && !remote.loading) {
                            val next = candidates(preferences)
                            currentCoroutineContext().ensureActive()
                            OfflineDownloads.syncSmart(next, preferences.limit)
                            if (allowed()) nextRefresh = now + REFRESH_MS
                        }
                        OfflineDownloads.resumeSmart()
                    }
                    delay(RETRY_MS)
                }
            }
        }
    }

    fun allowed(): Boolean {
        val settings = AppSettings.library.value
        if (!settings.smartDownloads || !MacMediaControls.smartNetworkAvailable()) return false
        return smartDownloadAllowed(true, true, false, MacMediaControls.smartBatteryPercent(),
            settings.smartDownloadMinBattery, appContext.filesDir.usableSpace)
    }

    private suspend fun candidates(preferences: Preferences): List<Song> {
        val hidden = DesktopHidden.list.value
        val dislikes = DesktopHidden.dislikes.value
        val history = hidden.records(Library.history()).filterNot { it.videoId in dislikes }.map { record ->
            // Older desktop plays counted starts without recording elapsed time.
            if (record.listenedMs == 0L) record.copy(listenedMs = 1L) else record
        }
        val mixes = if (preferences.mixes) {
            DesktopMixes.refresh(history)
            mixStarts(DesktopMixes.build(history).map { it.songs })
        } else emptyList()
        val fresh = smartDownloadCandidates(mixes, DesktopAccount.liked.value, history)
        val existing = if (DesktopAccount.library.value.problem != null) {
            OfflineDownloads.entries.value.values.filter { it.smartDownload && it.state == OfflineDownloads.State.DONE }.map { it.song }
        } else emptyList()
        return (fresh + existing).filter { it.videoId !in dislikes && !hidden.hides(it) && (!preferences.hideExplicit || it.isExplicit != true) }
    }
}
