package com.opentune.desktop

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.opentune.data.Http
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import org.schabi.newpipe.extractor.utils.JavaScript

/** Where the desktop keeps what it saves: ~/.opentune, read through the shared code's Context. */
internal val appContext by lazy { android.content.Context(File(System.getProperty("user.home"), ".opentune")) }

fun main(args: Array<String>) {
    if ("--self-check" in args) {
        // The Windows GUI launcher has no console. CI can read this report and the exit code.
        val report = args.firstOrNull { it.startsWith("--self-check-report=") }?.substringAfter('=')?.let(::File)
        selfCheck(report)
    }
    // The shared settings and music server, as the phone starts them.
    AppSettings.init(appContext)
    Subsonic.init(appContext)
    com.opentune.data.config.RemoteConfig.init(appContext)
    // What's playing, to the listener's Discord profile and Last.fm scrobbles, when they've turned them on.
    DiscordPresence.start()
    LastFmDesktop.start()
    // FFmpeg's natives unpack once on first use: done now, off the window's thread, so the first song doesn't wait.
    Thread({ Ffmpeg.available }, "ffmpeg-load").apply { isDaemon = true; start() }
    // Covers on the same HTTP client as everything else.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .build()
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OpenTune",
            icon = rememberLogo(Logo.APP),
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
            // On Windows the app draws its own title bar (see WindowsFrame), and on Windows 11 the
            // window is see-through for Mica or Acrylic to show. Both are fixed when it's made.
            undecorated = WindowsFrame.drawn,
            transparent = WindowsFrame.transparent,
        ) {
            window.minimumSize = java.awt.Dimension(760, 520)
            DesktopWindow.style(window)
            DesktopWindow.window = window
            DesktopWindow.close = ::exitApplication
            if (WindowsFrame.drawn) androidx.compose.runtime.LaunchedEffect(Unit) {
                // The handle exists once the window is on screen.
                repeat(20) {
                    if (window.isDisplayable && window.windowHandle != 0L && WindowsFrame.install(window.windowHandle)) return@LaunchedEffect
                    kotlinx.coroutines.delay(100)
                }
            }
            DesktopApp()
        }
    }
}

/**
 * Checks the packaged Java runtime and native dependencies without opening a
 * window, starting playback or touching the user's library.
 */
private fun selfCheck(report: File?): Nothing {
    val checks = listOf(
        "FFmpeg" to { check(Ffmpeg.available) { "didn't load" }; "loaded" },
        "GraalJS" to {
            val got = JavaScript.runOnGraal("var f = (a) => a?.at(-1) ?? 'x';", "f", "abc")
            check(got == "c") { "got $got" }
            "ran"
        },
        "SQLite" to { checkSqlite(); "stored and read a row" },
        "Skia" to {
            val image = ImageBitmap(1, 1)
            val paint = Paint().apply { color = Color.Green }
            Canvas(image).drawRect(0f, 0f, 1f, 1f, paint)
            check(image.asSkiaBitmap().getColor(0, 0) == Color.Green.toArgb()) { "couldn't render a pixel" }
            "rendered offscreen"
        },
    )
    var failed = false
    val lines = checks.map { (name, run) ->
        val result = runCatching(run)
        val status = result.fold({ "ok, $it" }, { failed = true; "FAILED, $it" })
        "$name: $status".also(::println)
    }
    report?.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    kotlin.system.exitProcess(if (failed) 1 else 0)
}

private fun checkSqlite() {
    val folder = Files.createTempDirectory("opentune-self-check-").toFile()
    try {
        val url = "jdbc:sqlite:${File(folder, "library.db").absolutePath}"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE songs(title TEXT NOT NULL)")
                statement.executeUpdate("INSERT INTO songs (title) VALUES ('OpenTune')")
            }
        }
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM songs").use { rows ->
                    check(rows.next() && rows.getString(1) == "OpenTune") { "saved row didn't survive reopening" }
                }
            }
        }
    } finally {
        folder.deleteRecursively()
    }
}
