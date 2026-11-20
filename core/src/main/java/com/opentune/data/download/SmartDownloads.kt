package com.opentune.data.download

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.*
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Automatically managed offline copies; manually saved songs never belong to this budget. */
object SmartDownloads {
    internal const val TAG = "smart-download"
    private const val REFRESH = "smart-download-refresh"
    private const val NOW = "smart-download-now"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    fun init(context: Context) {
        val app = context.applicationContext
        watching?.cancel()
        watching = scope.launch {
            AppSettings.library.map { Triple(it.smartDownloads, it.smartDownloadLimit, it.smartDownloadMinBattery to it.smartDownloadMixes) }
                .distinctUntilChanged().collect { (enabled, limit, _) ->
                    // WorkManager not set up yet (a test, or a process that never schedules work):
                    // nothing to schedule or cancel, rather than an exception thrown in the background.
                    val manager = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return@collect
                    if (!enabled) {
                        manager.cancelUniqueWork(REFRESH)
                        manager.cancelUniqueWork(NOW)
                        Downloads.entries.value.values.filter { it.smartDownload && it.state != DownloadState.DONE }
                            .forEach { Downloads.removeSmart(app, it.song.videoId, onlyPending = true) }
                    } else {
                        // Lowering the limit takes effect even while offline, without touching manual downloads.
                        Downloads.entries.value.values.filter { it.smartDownload }.sortedByDescending { it.addedAt }
                            .drop(limit.coerceIn(SMART_SONG_LIMIT)).forEach { Downloads.removeSmart(app, it.song.videoId) }
                        manager.enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS)
                                .setConstraints(constraints()).build())
                        manager.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequestBuilder<SmartDownloadWorker>().setConstraints(constraints()).build())
                    }
                }
        }
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

    internal fun allowed(context: Context): Boolean {
        val settings = AppSettings.library.value
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        val wifi = network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !manager.isActiveNetworkMetered
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return smartDownloadAllowed(settings.smartDownloads, wifi, charging, percent,
            settings.smartDownloadMinBattery, context.filesDir.usableSpace)
    }

    internal fun candidates(): List<Song> {
        val mixes = if (AppSettings.library.value.smartDownloadMixes) todaysMixSongs() else emptyList()
        return smartDownloadCandidates(mixes, LibraryStore.liked.value.map { it.toSong() }, History.records.value)
            .filter { com.opentune.data.isYouTubeId(it.videoId) && !LibraryStore.isDisliked(it.videoId) &&
                (!AppSettings.library.value.hideExplicit || it.isExplicit != true) }
            .distinctBy { it.videoId }
    }
}

/**
 * The start of each of today's mixes, the ones on Home: the daylist, the daily mixes, the genre
 * mixes and Discover. Only the first [MIX_SONGS_KEPT] of each, so the set changes little from one
 * day to the next while the first songs of every mix play with no connection.
 */
internal fun todaysMixSongs(nowMs: Long = System.currentTimeMillis()): List<Song> {
    val records = History.records.value
    val hidden = LibraryStore.hidden.value
    val genreOf = { artist: String -> com.opentune.data.metadata.ArtistGenres.genreOf(artist) }
    val mixes = listOfNotNull(com.opentune.data.history.Daylist.build(records, genreOf, nowMs, hidden = hidden)) +
        com.opentune.data.history.DailyMixes.build(records, nowMs, hidden = hidden) +
        com.opentune.data.history.MixSnapshots.mixes(com.opentune.data.history.MixSnapshots.GENRES).value.ifEmpty {
            com.opentune.data.history.GenreMixes.build(records, genreOf, nowMs, hidden = hidden)
        } +
        com.opentune.data.reco.GenreDiscover.mixes.value
    return mixStarts(mixes.map { it.songs })
}

/** The desired smart set, excluding copies the listener saved manually. */
internal fun smartDownloadSelection(candidates: List<Song>, entries: Map<String, DownloadEntry>, limit: Int): List<Song> =
    selectSmartDownloads(candidates, entries.filterValues { !it.smartDownload }.keys, limit)

class SmartDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AppSettings.library.value.smartDownloads) return Result.success()
        if (!SmartDownloads.allowed(applicationContext)) return Result.retry()
        val desired = smartDownloadSelection(SmartDownloads.candidates(), Downloads.entries.value,
            AppSettings.library.value.smartDownloadLimit)
        val ids = desired.mapTo(HashSet()) { it.videoId }
        Downloads.entries.value.values.filter { it.smartDownload && it.song.videoId !in ids }
            .forEach { Downloads.removeSmart(applicationContext, it.song.videoId) }
        for (song in desired) {
            currentCoroutineContext().ensureActive()
            if (!SmartDownloads.allowed(applicationContext)) return Result.retry()
            Downloads.enqueueSmart(applicationContext, song)
        }
        Downloads.flush()
        return Result.success()
    }
}
