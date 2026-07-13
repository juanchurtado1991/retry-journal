package com.ghostserializer.sync.sample.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File

internal actual object FilePicker {
    actual val isSupported: Boolean = true

    actual suspend fun pickFile(): PickedFile? = withContext(Dispatchers.IO) {
        // FileDialog.isVisible = true blocks the calling thread until the user picks or cancels
        // — that's exactly why this whole call runs on Dispatchers.IO instead of Compose's UI
        // dispatcher, which showing it from would freeze the window's redraw loop.
        val dialog = FileDialog(null as java.awt.Frame?, AppStrings.FILE_PICKER_TITLE, FileDialog.LOAD)
        dialog.isVisible = true

        val directory = dialog.directory
        val fileName = dialog.file
        if (directory == null || fileName == null) {
            return@withContext null
        }

        PickedFile(fileName, File(directory, fileName).readBytes())
    }
}
