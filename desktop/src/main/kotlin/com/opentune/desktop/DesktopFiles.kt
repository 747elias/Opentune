package com.opentune.desktop

import java.awt.EventQueue
import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun desktopFile(title: String, saveAs: String? = null): File? = desktopFiles(title, saveAs).firstOrNull()

internal suspend fun desktopFiles(
    title: String,
    saveAs: String? = null,
    multiple: Boolean = false,
    directories: Boolean = false,
    extensions: Set<String> = emptySet(),
): List<File> = suspendCancellableCoroutine { result ->
    EventQueue.invokeLater {
        if (!result.isActive) return@invokeLater
        result.resumeWith(runCatching {
            if (directories && !DesktopWindow.isMac) {
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                if (chooser.showOpenDialog(DesktopWindow.window) == JFileChooser.APPROVE_OPTION) {
                    listOfNotNull(chooser.selectedFile)
                } else emptyList()
            } else {
                nativeFileDialog(title, saveAs, multiple, directories, extensions)
            }
        })
    }
}

private fun nativeFileDialog(title: String, saveAs: String?, multiple: Boolean, directories: Boolean, extensions: Set<String>): List<File> {
    val mode = if (saveAs == null) FileDialog.LOAD else FileDialog.SAVE
    val dialog = FileDialog(DesktopWindow.window, title, mode)
    val directoryMode = "apple.awt.fileDialogForDirectories"
    val restoreMode = DesktopWindow.isMac && directories
    val previous = if (restoreMode) System.getProperty(directoryMode) else null
    try {
        if (restoreMode) System.setProperty(directoryMode, "true")
        if (saveAs != null) dialog.file = saveAs
        dialog.isMultipleMode = multiple
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { folder, name ->
                val file = File(folder, name)
                file.isDirectory || file.extension.lowercase() in extensions
            }
        }
        dialog.isVisible = true
        return dialog.files.toList()
    } finally {
        dialog.dispose()
        if (restoreMode && previous == null) System.clearProperty(directoryMode)
        if (restoreMode && previous != null) System.setProperty(directoryMode, previous)
    }
}
