package com.opentune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.autoeq.AutoEq
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HeadphoneSettings() {
    val equalizer by AppSettings.equalizer.collectAsState()
    var show by remember { mutableStateOf(false) }
    Text(equalizer.headphone?.let { "Headphone correction · ${it.name} (${it.source})" } ?: "Headphone correction")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { show = true }) { Text("Choose AutoEq profile") }
        if (equalizer.headphone != null) {
            TextButton(onClick = { AppSettings.updateEqualizer { it.copy(headphone = null) } }) { Text("Remove correction") }
        }
    }
    if (show) HeadphonePicker { show = false }
}

@Composable
private fun HeadphonePicker(close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AutoEq.Entry>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            entries = AutoEq.index()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = error.localizedMessage ?: "Couldn't load the AutoEq catalogue."
        }
    }
    val matches = remember(entries, query) { AutoEq.search(entries.orEmpty(), query) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Headphone correction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search headphone models") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                val status = message ?: when {
                    saving -> "Loading profile…"
                    entries == null -> "Loading AutoEq catalogue…"
                    query.isNotBlank() && matches.isEmpty() -> "No models match this search."
                    else -> null
                }
                status?.let { Text(it) }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(matches, key = { it.path }) { entry ->
                        TextButton(enabled = !saving, onClick = {
                            saving = true
                            message = null
                            scope.launch {
                                try {
                                    val profile = AutoEq.load(entry)
                                    AppSettings.updateEqualizer { it.copy(headphone = profile) }
                                    close()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    message = error.localizedMessage ?: "Couldn't load this AutoEq profile."
                                } finally { saving = false }
                            }
                        }) { Text("${entry.name} · ${entry.source}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}
