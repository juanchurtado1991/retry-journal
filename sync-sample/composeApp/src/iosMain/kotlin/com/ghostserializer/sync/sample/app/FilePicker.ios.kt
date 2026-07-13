package com.ghostserializer.sync.sample.app

internal actual object FilePicker {
    actual val isSupported: Boolean = false
    actual suspend fun pickFile(): PickedFile? = null
}
