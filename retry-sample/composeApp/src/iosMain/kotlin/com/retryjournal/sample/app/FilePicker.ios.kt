package com.retryjournal.sample.app

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

internal actual object FilePicker {
    actual val isSupported: Boolean = true

    private var activeDelegate: NSObject? = null

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual suspend fun pickFile(): PickedFile? = suspendCancellableCoroutine { continuation ->
        val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (rootViewController == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
        )

        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                if (url != null) {
                    val data = NSData.dataWithContentsOfURL(url)
                    if (data != null) {
                        val bytes = ByteArray(data.length.toInt())
                        bytes.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), data.bytes, data.length)
                        }
                        continuation.resume(PickedFile(url.lastPathComponent ?: "file", bytes))
                        activeDelegate = null
                        return
                    }
                }
                continuation.resume(null)
                activeDelegate = null
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                continuation.resume(null)
                activeDelegate = null
            }
        }

        activeDelegate = delegate

        picker.delegate = delegate
        rootViewController.presentViewController(picker, animated = true, completion = null)

        continuation.invokeOnCancellation {
            picker.dismissViewControllerAnimated(true, null)
            activeDelegate = null
        }
    }
}
