package com.nexauren1.filesmedia

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

/**
 * Queries files visible to this app through MediaStore.
 *
 * Non-media documents chosen via the system document picker are tracked separately by
 * MainActivity because not every DocumentsProvider item is indexed in MediaStore.
 */
class StorageRepository(private val resolver: ContentResolver) {

    fun query(category: String, query: String = ""): List<MediaEntry> {
        val collection = when (category) {
            "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "downloads" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
            else -> MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection: String?
        val args: Array<String>?
        when (category) {
            "documents" -> {
                // MIME types are often absent or generic for PDFs downloaded by browsers and
                // third-party file managers. Include extension matching as a safe fallback.
                val mimeColumn = MediaStore.MediaColumns.MIME_TYPE
                val nameColumn = MediaStore.MediaColumns.DISPLAY_NAME
                val mimeClauses = listOf(
                    "$mimeColumn = ?",
                    "$mimeColumn LIKE ?",
                    "$mimeColumn LIKE ?",
                    "$mimeColumn LIKE ?",
                    "$mimeColumn LIKE ?"
                )
                val nameClauses = listOf(
                    "$nameColumn LIKE ?", "$nameColumn LIKE ?", "$nameColumn LIKE ?",
                    "$nameColumn LIKE ?", "$nameColumn LIKE ?", "$nameColumn LIKE ?",
                    "$nameColumn LIKE ?", "$nameColumn LIKE ?", "$nameColumn LIKE ?",
                    "$nameColumn LIKE ?", "$nameColumn LIKE ?"
                )
                selection = "(" + (mimeClauses + nameClauses).joinToString(" OR ") + ")"
                args = arrayOf(
                    "application/pdf", "text/%", "application/vnd%", "application/msword",
                    "application/rtf",
                    "%.pdf", "%.txt", "%.doc", "%.docx", "%.xls", "%.xlsx",
                    "%.ppt", "%.pptx", "%.csv", "%.rtf", "%.odt"
                )
            }
            // Do not restrict "all" to non-null MIME values: some providers leave MIME blank,
            // which was causing common files to disappear from the unified view.
            "all", "starred", "large" -> {
                selection = null
                args = null
            }
            else -> {
                selection = null
                args = null
            }
        }

        val results = ArrayList<MediaEntry>()
        val needle = query.trim().lowercase(Locale.ROOT)
        val sort = MediaStore.MediaColumns.DATE_MODIFIED + " DESC"

        try {
            resolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext() && results.size < MAX_RESULTS) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else null
                    if (!isRelevant(category, name, mime)) continue
                    if (needle.isNotEmpty() && !name.lowercase(Locale.ROOT).contains(needle)) continue

                    val id = cursor.getLong(idIndex)
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).coerceAtLeast(0L) else 0L
                    val modified = if (dateIndex >= 0 && !cursor.isNull(dateIndex)) cursor.getLong(dateIndex).coerceAtLeast(0L) else 0L
                    results.add(
                        MediaEntry(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            mimeType = mime,
                            size = size,
                            modified = modified
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        return results
    }

    private fun isRelevant(category: String, name: String, mime: String?): Boolean {
        val type = (mime ?: "").lowercase(Locale.ROOT)
        val file = name.lowercase(Locale.ROOT)

        return when (category) {
            "images" -> type.startsWith("image/") ||
                file.hasExtension("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
            "videos" -> type.startsWith("video/") ||
                file.hasExtension("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "mpeg", "mpg", "ts")
            "audio" -> type.startsWith("audio/") ||
                file.hasExtension("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus", "wma", "aiff", "mid", "midi")
            "documents" -> type == "application/pdf" || type.startsWith("text/") ||
                type.contains("officedocument") || type.contains("msword") ||
                type.contains("spreadsheet") || type.contains("presentation") ||
                type == "application/rtf" ||
                file.hasExtension("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "rtf", "odt")
            "downloads" -> true
            "all", "starred", "large" -> true
            else -> true
        }
    }

    private fun String.hasExtension(vararg extensions: String): Boolean {
        val dot = lastIndexOf('.')
        if (dot < 0 || dot == lastIndex) return false
        return substring(dot + 1) in extensions
    }

    companion object {
        private const val MAX_RESULTS = 500

        fun formatSize(bytes: Long): String {
            if (bytes < 1024L) return bytes.toString() + " B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var index = -1
            do {
                value /= 1024.0
                index++
            } while (value >= 1024.0 && index < units.lastIndex)
            return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
        }

        fun formatDate(seconds: Long): String {
            if (seconds <= 0) return ""
            val date = java.util.Date(seconds * 1000L)
            return java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(date)
        }
    }
}
