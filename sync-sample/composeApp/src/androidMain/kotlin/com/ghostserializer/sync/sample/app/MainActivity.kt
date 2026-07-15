package com.ghostserializer.sync.sample.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ghostserializer.sync.sample.ui.App

class MainActivity : ComponentActivity() {
    companion object {
        private var pickFileCallback: ((PickedFile?) -> Unit)? = null
        
        internal fun pickFile(activity: MainActivity, callback: (PickedFile?) -> Unit) {
            pickFileCallback = callback
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            activity.startActivityForResult(Intent.createChooser(intent, "Select File"), 1001)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                val pickedFile = readPickedFile(uri)
                pickFileCallback?.invoke(pickedFile)
            } else {
                pickFileCallback?.invoke(null)
            }
            pickFileCallback = null
        }
    }

    private fun readPickedFile(uri: Uri): PickedFile? {
        return try {
            val contentResolver = contentResolver
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIndex) else null
            } ?: "file"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            PickedFile(fileName, bytes)
        } catch (e: Exception) {
            null
        }
    }
}
