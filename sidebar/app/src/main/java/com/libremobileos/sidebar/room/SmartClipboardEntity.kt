package com.libremobileos.sidebar.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class SmartClipboardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val text: String? = null,
    val fileName: String? = null,
    val imagePath: String? = null,
    val mimeType: String? = null,
    val contentHash: String,
    val createdAt: Long,
    val isPinned: Boolean = false,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_FILE = "file"
    }
}
