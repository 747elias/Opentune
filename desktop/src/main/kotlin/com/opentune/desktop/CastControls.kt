package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.ui.theme.Text

@Composable
internal fun CastButton() {
    val output by DesktopCast.active.collectAsState()
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }) {
        Icon(Icons.Rounded.Cast, "Play on another device", tint = if (output != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
    if (show) CastChooser { show = false }
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
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
