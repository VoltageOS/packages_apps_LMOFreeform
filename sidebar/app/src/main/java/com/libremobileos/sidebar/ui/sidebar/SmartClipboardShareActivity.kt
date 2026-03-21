package com.libremobileos.sidebar.ui.sidebar

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.service.SidebarService

class SmartClipboardShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clipData = buildImportClipData(intent)
        if (clipData != null) {
            startServiceAsUser(
                Intent(this, SidebarService::class.java).apply {
                    action = SidebarService.ACTION_IMPORT_TO_SMART_CLIPBOARD
                    this.clipData = clipData
                },
                UserHandle.CURRENT
            )
        } else {
            Toast.makeText(applicationContext, R.string.smart_clipboard_unsupported, Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun buildImportClipData(intent: Intent): ClipData? {
        intent.clipData?.let { return it }

        val label = getString(R.string.smart_clipboard_label)
        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        if (!processText.isNullOrBlank()) {
            return ClipData.newPlainText(label, processText)
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            return ClipData.newPlainText(label, text)
        }

        val singleStream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (singleStream != null) {
            return ClipData.newUri(contentResolver, label, singleStream)
        }

        val multipleStreams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?.filterNotNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return ClipData.newUri(contentResolver, label, multipleStreams.first()).apply {
            multipleStreams.drop(1).forEach { uri ->
                addItem(ClipData.Item(uri))
            }
        }
    }
}
