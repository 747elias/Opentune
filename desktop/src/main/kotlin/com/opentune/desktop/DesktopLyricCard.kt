package com.opentune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.opentune.data.lyrics.LyricCardFormat
import com.opentune.data.model.Song
import com.opentune.ui.theme.Text
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.AttributedString
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Image as SkiaImage

internal enum class LyricCardStyle(val label: String) { COVER("Cover"), COLOUR("Colour"), LIGHT("Light") }

@Composable
internal fun DesktopLyricCard(song: Song, lines: List<String>, startAt: Int, onClose: () -> Unit) {
    if (lines.isEmpty()) return
    var chosen by remember(song.videoId, lines) { mutableStateOf(setOf(startAt.coerceIn(lines.indices))) }
    var story by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(LyricCardStyle.COVER) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val artwork by produceState<BufferedImage?>(null, song.thumbnailUrl) {
        value = withTimeoutOrNull(8_000) {
            val path = song.thumbnailUrl ?: return@withTimeoutOrNull null
            val request = ImageRequest.Builder(context).data(path).size(720).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@withTimeoutOrNull null
            withContext(Dispatchers.Default) {
                SkiaImage.makeFromBitmap(result.image.toBitmap()).use { loaded ->
                    loaded.encodeToData()?.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
                }
            }
        }
    }
    val selected = LyricCardFormat.selectedLines(lines, chosen)
    val rendered by produceState<RenderedLyricCard?>(null, song, selected, story, style, artwork) {
        value = if (selected.isEmpty()) null else withContext(Dispatchers.Default) {
            val bitmap = drawLyricCard(artwork, selected, song, story, style)
            val png = ByteArrayOutputStream().use { out ->
                check(ImageIO.write(bitmap, "png", out))
                out.toByteArray()
            }
            RenderedLyricCard(song, selected, story, style, artwork, png)
        }
    }
    val active = rendered?.takeIf { it.song == song && it.lines == selected && it.story == story && it.style == style && it.cover === artwork }
    val preview = remember(active) {
        active?.let { SkiaImage.makeFromEncoded(it.png).use { image -> SkiaBitmap.makeFromImage(image).asComposeImageBitmap() } }
    }
    val list = rememberLazyListState()
    LaunchedEffect(song.videoId) { list.scrollToItem((startAt - 2).coerceAtLeast(0).coerceAtMost(lines.lastIndex)) }

    DialogWindow(onCloseRequest = onClose, title = "Share lyrics", state = rememberDialogState(width = 760.dp, height = 640.dp)) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Share lyrics", style = MaterialTheme.typography.headlineSmall)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${chosen.size} of ${LyricCardFormat.MAX_LINES} lines selected", style = MaterialTheme.typography.bodyMedium)
                            LazyColumn(state = list) {
                                itemsIndexed(lines) { index, line ->
                                    val checked = index in chosen
                                    val canChoose = checked || chosen.size < LyricCardFormat.MAX_LINES
                                    Row(Modifier.fillMaxWidth().clickable(enabled = canChoose) {
                                        chosen = if (checked) chosen - index else chosen + index
                                        feedback = null
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked, onCheckedChange = null, enabled = canChoose, modifier = Modifier.padding(9.dp))
                                        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 5.dp))
                                    }
                                }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            preview?.let { Image(it, "Lyric card preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(!story, { story = false; feedback = null }, { Text("Post") })
                        FilterChip(story, { story = true; feedback = null }, { Text("Story") })
                        LyricCardStyle.entries.forEach { option ->
                            FilterChip(style == option, { style = option; feedback = null }, { Text(option.label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
                        TextButton(onClick = onClose) { Text("Close") }
                        TextButton(enabled = active != null && !saving, onClick = {
                            val card = active ?: return@TextButton
                            feedback = runCatching {
                                val image = ImageIO.read(ByteArrayInputStream(card.png))
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(CardClipboard(image), null)
                            }.fold({ "Image copied" }, { "Couldn't copy the image" })
                        }) { Text("Copy image") }
                        Button(enabled = active != null && !saving, onClick = {
                            val card = active ?: return@Button
                            saving = true
                            scope.launch {
                                try {
                                    val proposed = "OpenTune-lyrics${if (card.story) "-story" else ""}.png"
                                    val file = desktopFile("Save lyric card", saveAs = proposed, owner = window) ?: return@launch
                                    withContext(Dispatchers.IO) { saveCard(file, card.png) }
                                    feedback = "Image saved"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    feedback = "Couldn't save the image"
                                } finally {
                                    saving = false
                                }
                            }
                        }) { Text("Save PNG") }
                    }
                }
            }
        }
    }
}

private data class RenderedLyricCard(
    val song: Song,
    val lines: List<String>,
    val story: Boolean,
    val style: LyricCardStyle,
    val cover: BufferedImage?,
    val png: ByteArray,
)

private class CardClipboard(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any = if (isDataFlavorSupported(flavor)) image else throw UnsupportedFlavorException(flavor)
}

internal fun saveCard(file: File, png: ByteArray) {
    val destination = file.absoluteFile.toPath()
    val staged = Files.createTempFile(destination.parent, ".opentune-lyric-", ".png")
    try {
        Files.write(staged, png)
        try {
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(staged)
    }
}

internal fun drawLyricCard(cover: BufferedImage?, lines: List<String>, song: Song, story: Boolean, style: LyricCardStyle): BufferedImage {
    val width = LyricCardFormat.WIDTH
    val height = LyricCardFormat.height(story)
    val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = result.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val tone = cover?.let { averageColour(it) } ?: Color(84, 71, 121)
        val light = style == LyricCardStyle.LIGHT
        if (style == LyricCardStyle.COVER && cover != null) {
            val blurred = BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB)
            blurred.createGraphics().let { tiny ->
                tiny.drawImage(cover, 0, 0, 30, 30, null)
                tiny.dispose()
            }
            g.drawImage(blurred, 0, 0, width, height, null)
            g.paint = GradientPaint(0f, 0f, Color(0, 0, 0, 180), 0f, height.toFloat(), Color(0, 0, 0, 232))
        } else {
            g.paint = GradientPaint(0f, 0f, if (light) Color(248, 244, 238) else tone.shade(.68),
                0f, height.toFloat(), if (light) Color(227, 219, 215) else Color(17, 15, 23))
        }
        g.fillRect(0, 0, width, height)

        val ink = if (light) Color(32, 26, 34) else Color.WHITE
        val soft = if (light) Color(80, 65, 80) else Color(225, 223, 232)
        val margin = 78
        val artSize = 130
        val footerTop = height - margin - artSize
        g.color = soft
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 37)
        g.drawString("OpenTune", width - margin - g.fontMetrics.stringWidth("OpenTune"), margin + 36)
        cover?.let { album ->
            val clip = g.clip
            g.clip(RoundRectangle2D.Double(margin.toDouble(), footerTop.toDouble(), artSize.toDouble(), artSize.toDouble(), 24.0, 24.0))
            val side = minOf(album.width, album.height)
            val x = (album.width - side) / 2
            val y = (album.height - side) / 2
            g.drawImage(album, margin, footerTop, margin + artSize, footerTop + artSize, x, y, x + side, y + side, null)
            g.clip = clip
        }
        val textLeft = if (cover == null) margin else margin + artSize + 30
        g.color = ink
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 39)
        g.drawBounded(song.title, textLeft, footerTop + 52, width - margin - textLeft)
        g.color = soft
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 32)
        g.drawBounded(song.artist, textLeft, footerTop + 102, width - margin - textLeft)

        val top = margin + 105f
        val bottom = footerTop - 54f
        var size = 79
        var layout: List<TextLayout>
        var total: Float
        do {
            val font = Font(Font.SANS_SERIF, Font.BOLD, size)
            layout = lines.take(LyricCardFormat.MAX_LINES).flatMap { line ->
                val paragraph = AttributedString(line.ifBlank { " " }).apply { addAttribute(TextAttribute.FONT, font) }.iterator
                val breaks = LineBreakMeasurer(paragraph, g.fontRenderContext)
                buildList {
                    while (breaks.position < paragraph.endIndex) add(breaks.nextLayout((width - margin * 2).toFloat()))
                }
            }
            total = layout.sumOf { (it.ascent + it.descent + it.leading + size * .19f).toDouble() }.toFloat()
            if (total <= bottom - top || size <= 28) break
            size -= 3
        } while (true)
        var y = if (story) top + (bottom - top - total).coerceAtLeast(0f) / 2 else top
        g.color = ink
        g.clipRect(margin, top.toInt(), width - margin * 2, (bottom - top).toInt())
        for (line in layout) {
            y += line.ascent
            val x = if (line.isLeftToRight) margin.toFloat() else width - margin - line.advance
            line.draw(g, x, y)
            y += line.descent + line.leading + size * .19f
        }
    } finally {
        g.dispose()
    }
    return result
}

private fun Color.shade(factor: Double): Color = Color((red * factor).toInt(), (green * factor).toInt(), (blue * factor).toInt())

private fun averageColour(image: BufferedImage): Color {
    val tiny = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    tiny.createGraphics().let { graphics -> graphics.drawImage(image, 0, 0, 8, 8, null); graphics.dispose() }
    val colours = (0 until 8).flatMap { y -> (0 until 8).map { x -> Color(tiny.getRGB(x, y)) } }
    return Color(colours.sumOf { it.red } / 64, colours.sumOf { it.green } / 64, colours.sumOf { it.blue } / 64)
}

private fun Graphics2D.drawBounded(text: String, x: Int, baseline: Int, width: Int) {
    val end = if (fontMetrics.stringWidth(text) <= width) text else {
        var count = text.length
        while (count > 0 && fontMetrics.stringWidth(text.substring(0, count) + "…") > width) {
            count = text.offsetByCodePoints(count, -1)
        }
        text.substring(0, count) + "…"
    }
    drawString(end, x, baseline)
}
