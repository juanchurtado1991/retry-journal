package com.ghostserializer.sync.sample.app

/** Lets the user pick a real file from disk to upload — only where the platform can show a native
 * file-picker UI synchronously from this demo's code (desktop). Android/iOS need a
 * platform-specific picker flow (an Activity result launcher, a UIDocumentPickerViewController)
 * this sample doesn't wire up, so [isSupported] is `false` there. */
internal expect object FilePicker {
    val isSupported: Boolean
    suspend fun pickFile(): PickedFile?
}
