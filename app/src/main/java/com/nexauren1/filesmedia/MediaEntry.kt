package com.nexauren1.filesmedia

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

enum class EntrySource {
    MEDIA_STORE,
    SAF
}

data class MediaEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val modified: Long,
    val source: EntrySource = EntrySource.MEDIA_STORE,
    val isDirectory: Boolean = false,
    val document: DocumentFile? = null
)
