package com.opentune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.i18n.Tr
import com.opentune.ui.theme.Text
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DesktopLanguage {
    private val prefs by lazy { appContext.getSharedPreferences("desktop-language", 0) }
    private val selected = MutableStateFlow("")
    val language = selected.asStateFlow()
    val choices = listOf("", "en") + Tr.TAGS.sortedBy(::displayName)

    fun init() {
        val tag = prefs.getString("tag", "").orEmpty()
        Tr.use(tag)
        selected.value = tag
    }

    fun select(tag: String) {
        if (tag !in choices) return
        Tr.use(tag)
        prefs.edit().putString("tag", tag).apply()
        selected.value = tag
    }

    fun displayName(tag: String): String {
        if (tag.isBlank()) return "System language"
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}

@Composable
internal fun DesktopLanguageSettings() {
    val selected by DesktopLanguage.language.collectAsState()
    var open by remember { mutableStateOf(false) }
    Text("App language")
    Box {
        OutlinedButton(onClick = { open = true }) { Text(DesktopLanguage.displayName(selected)) }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            DesktopLanguage.choices.forEach { tag ->
                DropdownMenuItem(text = { Text(DesktopLanguage.displayName(tag)) }, onClick = {
                    DesktopLanguage.select(tag)
                    open = false
                })
            }
        }
    }
}
