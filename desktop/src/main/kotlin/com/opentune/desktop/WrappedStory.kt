package com.opentune.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.opentune.data.history.Wrapped
import com.opentune.data.settings.AppSettings
import com.opentune.ui.theme.Text
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StorySlide(val label: String, val headline: String, val lines: List<String> = emptyList(), val cover: String? = null)

private fun storySlides(summary: Wrapped.Summary, period: String) = buildList {
    add(StorySlide("OpenTune Wrapped", period, listOf("Your listening on this computer")))
    add(StorySlide("Time listened", "${summary.minutes} minutes", listOf("${summary.plays} plays")))
    summary.topArtists.firstOrNull()?.let { artist ->
        add(StorySlide("Top artist", artist.title, listOf("${artist.plays} plays"), artist.thumbnailUrl))
    }
    if (summary.topArtists.size > 1) add(StorySlide("Top artists", "${summary.artists} artists", summary.topArtists.map { it.title }))
    summary.topSongs.firstOrNull()?.let { song ->
        add(StorySlide("Top song", song.title, listOf(song.subtitle, "${song.plays} plays"), song.thumbnailUrl))
    }
    if (summary.topSongs.size > 1) add(StorySlide("Top songs", "${summary.songs} songs", summary.topSongs.map { it.title }))
    add(StorySlide("Listening clock", summary.clock.title, listOf(summary.clock.line)))
    val busiestMonth = summary.months.maxByOrNull { it.second }?.takeIf { it.second > 0 }
    val calendar = listOfNotNull(
        busiestMonth?.let { (month, minutes) -> "Busiest month · $month · $minutes minutes" },
        "Busiest day · ${summary.busiestDayMinutes} minutes",
    )
    add(StorySlide("Listening streak", "${summary.streakDays} days", calendar))
    val memories = listOfNotNull(
        summary.onRepeat?.let { "On repeat · ${it.title} · ${it.plays} plays in one day" },
        summary.first?.let { "First track · ${it.title} · ${it.subtitle}" },
    )
    (summary.onRepeat ?: summary.first)?.let { add(StorySlide("Listening memories", it.title, memories)) }
    add(StorySlide("OpenTune Wrapped · $period", "${summary.minutes} minutes", posterLines(summary)))
}

private fun posterLines(summary: Wrapped.Summary) = listOfNotNull(
    "${summary.plays} plays · ${summary.songs} songs · ${summary.artists} artists",
    summary.topArtists.firstOrNull()?.let { "Top artist · ${it.title}" },
    summary.topSongs.firstOrNull()?.let { "Top song · ${it.title}" },
    "${summary.clock.title} · ${summary.streakDays} day streak",
)

private data class StoryColors(val background: Color, val ink: Color, val shape: Color)

private val storyColors = listOf(
    StoryColors(Color(0xFFF3EDE2), Color(0xFF1C1A17), Color(0xFFE2583E)),
    StoryColors(Color(0xFF16302B), Color(0xFFF3EDE2), Color(0xFFA7C4A0)),
    StoryColors(Color(0xFFCDBDF2), Color(0xFF16153A), Color(0xFF2D2A6E)),
    StoryColors(Color(0xFFF4D35E), Color(0xFF2A1A0A), Color(0xFFEE6C3B)),
)

@Composable
internal fun WrappedStory(summary: Wrapped.Summary, period: String, onClose: () -> Unit) {
    val slides = remember(summary, period) { storySlides(summary, period) }
    var slide by remember(summary, period) { mutableIntStateOf(0) }
    var advancing by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val ui by AppSettings.ui.collectAsState()
    val songs = remember(summary) { summary.topSongs.mapNotNull { it.song } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(slide, advancing, saving) {
        if (advancing && !saving && slide < slides.lastIndex) {
            delay(7_000)
            slide++
        }
    }
    DialogWindow(onCloseRequest = onClose, title = "OpenTune Wrapped", state = rememberDialogState(width = 740.dp, height = 590.dp)) {
        DesktopTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { (slide + 1).toFloat() / slides.size }, modifier = Modifier.fillMaxWidth())
                    Crossfade(
                        targetState = slide,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        animationSpec = tween(if (ui.reduceAnimation) 0 else 500),
                    ) { index -> StoryCard(slides[index], storyColors[index % storyColors.size]) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = slide > 0, onClick = { slide-- }) { Text("Previous") }
                        TextButton(onClick = { advancing = !advancing }) { Text(if (advancing) "Pause story" else "Resume story") }
                        TextButton(enabled = slide < slides.lastIndex, onClick = { slide++ }) { Text("Next") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        feedback?.let { Text(it, modifier = Modifier.weight(1f)) }
                        if (slide == slides.lastIndex) {
                            TextButton(enabled = songs.isNotEmpty(), onClick = {
                                Player.playAll(songs, 0)
                                onClose()
                            }) { Text("Play top tracks") }
                            TextButton(enabled = songs.isNotEmpty(), onClick = {
                                Player.playAll(songs.shuffled(), 0)
                                onClose()
                            }) { Text("Shuffle") }
                        }
                        Button(enabled = !saving, onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val file = desktopFile("Save Wrapped poster", saveAs = "OpenTune-Wrapped.png", owner = window) ?: return@launch
                                    withContext(Dispatchers.IO) { saveCard(file, wrappedPoster(summary, period)) }
                                    feedback = "Poster saved"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    feedback = "Couldn't save the poster"
                                } finally {
                                    saving = false
                                }
                            }
                        }) { Text("Save poster") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryCard(slide: StorySlide, colors: StoryColors) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(colors.background)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.94f, size.height * 1.15f)
            val scale = size.width * 0.34f
            drawCircle(colors.shape.copy(alpha = 0.18f), radius = scale, center = center)
            drawCircle(colors.shape.copy(alpha = 0.14f), radius = scale * 0.70f, center = center)
        }
        Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(slide.label, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                Text(slide.headline, color = colors.ink, fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                slide.lines.forEach { Text(it, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            slide.cover?.let { Cover(it, 144.dp, RoundedCornerShape(14.dp)) }
        }
    }
}

private fun wrappedPoster(summary: Wrapped.Summary, period: String): ByteArray {
    val image = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    try {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        canvas.color = java.awt.Color(0xF3EDE2)
        canvas.fillRect(0, 0, image.width, image.height)
        canvas.color = java.awt.Color(0xE2583E)
        canvas.fillOval(220, 1320, 1000, 1000)
        canvas.color = java.awt.Color(0x1C1A17)
        val inset = 88
        fun line(value: String, y: Int, font: Font) {
            canvas.font = font
            val available = image.width - 2 * inset
            var end = value.length
            while (end > 0 && canvas.fontMetrics.stringWidth(value.take(end) + "…") > available) end--
            canvas.drawString(if (end == value.length) value else value.take(end).trimEnd() + "…", inset, y)
        }
        line("OpenTune Wrapped · $period", 220, Font(Font.SANS_SERIF, Font.PLAIN, 38))
        line("${summary.minutes} minutes", 560, Font(Font.SERIF, Font.BOLD, 90))
        posterLines(summary).forEachIndexed { index, text -> line(text, 710 + index * 80, Font(Font.SANS_SERIF, Font.PLAIN, 40)) }
    } finally {
        canvas.dispose()
    }
    return ByteArrayOutputStream().use { stream ->
        check(ImageIO.write(image, "png", stream))
        stream.toByteArray()
    }
}
