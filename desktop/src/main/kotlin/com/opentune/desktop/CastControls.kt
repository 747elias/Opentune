package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.ui.theme.Text
import javax.sound.sampled.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class OutputPanel { OUTPUT, CAST, PIPELINE }

@Composable
internal fun AudioOutputButton() {
    val output by DesktopCast.active.collectAsState()
    var panel by remember { mutableStateOf<OutputPanel?>(null) }
    val close = { panel = null }
    IconButton(onClick = { panel = OutputPanel.OUTPUT }) {
        Icon(Icons.Rounded.Speaker, "Audio output", tint = if (output != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
    when (panel) {
        OutputPanel.OUTPUT -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Audio output") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioOutputPicker()
                    OutlinedButton(onClick = { panel = OutputPanel.CAST }, modifier = Modifier.fillMaxWidth()) {
                        Text(output?.target?.name ?: "Cast to another device")
                    }
                    OutlinedButton(onClick = { panel = OutputPanel.PIPELINE }, modifier = Modifier.fillMaxWidth()) {
                        Text("Audio pipeline")
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Close") } },
        )
        OutputPanel.CAST -> CastChooser { panel = OutputPanel.OUTPUT }
        OutputPanel.PIPELINE -> SignalPathDialog { panel = OutputPanel.OUTPUT }
        null -> Unit
    }
}

@Composable
internal fun AudioOutputPicker() {
    val selected by DesktopAudioOutput.selected.collectAsState()
    val devices by produceState(emptyList<DesktopAudioOutput.Device>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { DesktopAudioOutput.devices() }
            delay(3_000)
        }
    }
    var picking by remember { mutableStateOf(false) }
    val selectedName = devices.firstOrNull { it.id == selected }?.name
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedName ?: "System default output")
        }
        DropdownMenu(picking, onDismissRequest = { picking = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text("System default output") }, onClick = {
                DesktopAudioOutput.select(null)
                picking = false
            })
            for (device in devices) {
                DropdownMenuItem(text = { Text(device.name) }, onClick = {
                    DesktopAudioOutput.select(device.id)
                    picking = false
                })
            }
        }
    }
}

@Composable
private fun SignalPathDialog(onClose: () -> Unit) {
    val path by Player.signalPath.collectAsState()
    val quality by Player.quality.collectAsState()
    val cast by DesktopCast.active.collectAsState()
    val activePath = path
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Audio pipeline") },
        text = {
            if (activePath == null) Text("Play a track to see its audio pipeline.")
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                quality?.let { PipelineStage("Source", "${it.source} · ${it.detail}") }
                PipelineStage("Decoder", listOfNotNull(activePath.decoder, activePath.codec).joinToString(" · "))
                activePath.route?.let { route ->
                    val format = route.format
                    val type = if (format.encoding == AudioFormat.Encoding.PCM_FLOAT) "float PCM" else "PCM"
                    PipelineStage("Output format", "${format.sampleRate.toInt()} Hz · ${format.sampleSizeInBits}-bit $type · ${format.channels} ch")
                }
                PipelineStage("Audio output", cast?.target?.name ?: activePath.route?.device ?: "System default output")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Back") } },
    )
}

@Composable
private fun PipelineStage(label: String, detail: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CastChooser(onClose: () -> Unit) {
    val output by DesktopCast.active.collectAsState()
    val targets by DesktopCast.targets.collectAsState()
    val busy by DesktopCast.busy.collectAsState()
    val error by DesktopCast.error.collectAsState()
    LaunchedEffect(Unit) { DesktopCast.discover() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Play on another device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (output != null) {
                    Text("Playing on ${output?.target?.name}")
                    OutlinedButton(enabled = !busy, onClick = DesktopCast::disconnect) { Text("Play on this computer") }
                }
                if (busy) Text("Searching or connecting…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!busy && targets.isEmpty()) Text("No Chromecast or DLNA receivers found on this network.")
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(targets, key = { it.id }) { target ->
                        OutlinedButton(onClick = { DesktopCast.connect(target) }, enabled = !busy && output?.target?.id != target.id,
                            modifier = Modifier.fillMaxWidth()) { Text(target.name) }
                    }
                }
                TextButton(enabled = !busy, onClick = DesktopCast::discover) { Text("Search again") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Back") } },
    )
}
