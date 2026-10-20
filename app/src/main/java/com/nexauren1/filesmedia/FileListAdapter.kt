package com.nexauren1.filesmedia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small RecyclerView adapter with a persistent list/grid presentation.
 * Thumbnail work uses one bounded shared executor rather than creating new threads every time
 * the user changes category.
 */
class FileListAdapter(
    private val context: Context,
    private val onOpen: (MediaEntry) -> Unit,
    private val onMenu: (MediaEntry, View) -> Unit,
    private val initialGridMode: Boolean = false,
    private val onSelectionChanged: (List<MediaEntry>, Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<FileListAdapter.EntryHolder>() {

    private var entries: List<MediaEntry> = emptyList()
    private val gridMode = initialGridMode
    private val selectedUris = LinkedHashSet<String>()
    private var selectionMode = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        setHasStableIds(false)
    }

    fun submit(items: List<MediaEntry>) {
        entries = items.toList()
        val available = entries.map { it.uri.toString() }.toSet()
        selectedUris.retainAll(available)
        if (selectedUris.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries(), selectionMode)
    }

    fun selectedEntries(): List<MediaEntry> =
        entries.filter { selectedUris.contains(it.uri.toString()) }

    fun isSelectionModeActive(): Boolean = selectionMode

    fun selectAll() {
        if (entries.isEmpty()) return
        selectionMode = true
        selectedUris.clear()
        entries.forEach { selectedUris.add(it.uri.toString()) }
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries(), true)
    }

    fun clearSelection() {
        selectedUris.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(emptyList(), false)
    }

    private fun toggleSelection(entry: MediaEntry) {
        selectionMode = true
        val key = entry.uri.toString()
        if (!selectedUris.add(key)) selectedUris.remove(key)
        if (selectedUris.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries(), selectionMode)
    }

    private fun selectOnLongPress(entry: MediaEntry) {
        val key = entry.uri.toString()
        if (!selectionMode) {
            selectionMode = true
            selectedUris.clear()
            selectedUris.add(key)
        } else {
            if (!selectedUris.add(key)) selectedUris.remove(key)
            if (selectedUris.isEmpty()) selectionMode = false
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries(), selectionMode)
    }

    override fun getItemViewType(position: Int): Int = if (gridMode) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
        return if (viewType == TYPE_GRID) createGridHolder() else createListHolder()
    }

    private fun createListHolder(): EntryHolder {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
            minimumHeight = dp(76)
            isClickable = true
            isFocusable = true
        }
        val thumbnailContainer = FrameLayout(context).apply {
            background = rounded(Color.rgb(235, 234, 244), 14)
        }
        val thumbnail = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val fallback = iconLabel()
        thumbnailContainer.addView(thumbnail, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        thumbnailContainer.addView(fallback, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        row.addView(thumbnailContainer, LinearLayout.LayoutParams(dp(58), dp(58)))

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        val name = fileNameLabel(14.5f)
        val details = detailLabel()
        labels.addView(name)
        labels.addView(details)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val menu = menuButton()
        row.addView(menu, LinearLayout.LayoutParams(dp(36), dp(50)))
        return EntryHolder(row, thumbnail, fallback, name, details, menu)
    }

    private fun createGridHolder(): EntryHolder {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(9))
            background = rounded(Color.WHITE, 18)
            isClickable = true
            isFocusable = true
            clipToPadding = true
        }
        val preview = FrameLayout(context).apply {
            background = rounded(Color.rgb(235, 234, 244), 14)
        }
        val thumbnail = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        val fallback = iconLabel().apply { textSize = 30f }
        preview.addView(thumbnail, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        preview.addView(fallback, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val menu = menuButton().apply {
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(112, 25, 26, 38), 18)
            elevation = dp(1).toFloat()
        }
        preview.addView(menu, FrameLayout.LayoutParams(dp(34), dp(38), Gravity.TOP or Gravity.END))
        card.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
        ))

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(8), dp(3), 0)
        }
        val name = fileNameLabel(13.5f).apply {
            maxLines = 2
            minHeight = dp(34)
        }
        val details = detailLabel().apply {
            setPadding(0, dp(4), 0, 0)
        }
        labels.addView(name)
        labels.addView(details)
        card.addView(labels, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.minimumHeight = dp(176)
        return EntryHolder(card, thumbnail, fallback, name, details, menu)
    }

    private fun iconLabel(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 24f
        setTextColor(Color.rgb(80, 88, 174))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun fileNameLabel(size: Float): TextView = TextView(context).apply {
        textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.rgb(32, 33, 42))
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        includeFontPadding = true
    }

    private fun detailLabel(): TextView = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.rgb(105, 106, 119))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun menuButton(): TextView = TextView(context).apply {
        text = "⋮"
        textSize = 25f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(75, 76, 88))
        isClickable = true
        isFocusable = true
        contentDescription = "Mais opções do ficheiro"
    }

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        val entry = entries[position]
        holder.boundUri = entry.uri
        holder.name.text = entry.name
        holder.details.text = if (entry.isDirectory) "Pasta" else detailText(entry)
        holder.fallback.text = fallbackIcon(entry)
        holder.thumbnail.setImageDrawable(null)
        holder.thumbnail.tag = entry.uri
        holder.thumbnail.visibility = View.GONE
        holder.fallback.visibility = View.VISIBLE

        val mime = (entry.mimeType ?: "").lowercase(Locale.ROOT)
        val isPdf = mime == "application/pdf" || entry.name.endsWith(".pdf", ignoreCase = true)
        val canHavePreview = mime.startsWith("image/") || mime.startsWith("video/") || isPdf
        if (!entry.isDirectory && Build.VERSION.SDK_INT >= 29 && canHavePreview) {
            thumbnailExecutor.execute {
                val bitmap: Bitmap? = try {
                    context.contentResolver.loadThumbnail(
                        entry.uri, Size(dp(if (gridMode) 240 else 116), dp(if (gridMode) 240 else 116)),
                        CancellationSignal()
                    )
                } catch (_: Exception) {
                    null
                }
                mainHandler.post {
                    if (bitmap == null) return@post
                    if (holder.thumbnail.tag == entry.uri) {
                        holder.thumbnail.setImageBitmap(bitmap)
                        holder.thumbnail.visibility = View.VISIBLE
                        holder.fallback.visibility = View.GONE
                    } else {
                        bitmap.recycle()
                    }
                }
            }
        }

        val selected = selectedUris.contains(entry.uri.toString())
        holder.itemView.background = if (selected) {
            rounded(Color.rgb(220, 226, 255), if (gridMode) 18 else 14)
        } else if (gridMode) {
            rounded(Color.WHITE, 18)
        } else {
            rounded(Color.TRANSPARENT, 14)
        }
        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelection(entry) else onOpen(entry)
        }
        holder.itemView.setOnLongClickListener {
            selectOnLongPress(entry)
            true
        }
        holder.menu.setOnClickListener { onMenu(entry, holder.menu) }
        holder.menu.contentDescription = "Ações para " + entry.name
        holder.itemView.contentDescription = entry.name +
            if (entry.isDirectory) ", pasta" else ", " + detailText(entry) +
                if (selected) ", selecionado" else ""
    }

    override fun getItemCount(): Int = entries.size

    private fun detailText(entry: MediaEntry): String {
        val size = StorageRepository.formatSize(entry.size)
        val date = StorageRepository.formatDate(entry.modified)
        return if (date.isEmpty()) size else size + " • " + date
    }

    private fun fallbackIcon(entry: MediaEntry): String {
        if (entry.isDirectory) return "📁"
        val mime = (entry.mimeType ?: "").lowercase(Locale.ROOT)
        val name = entry.name.lowercase(Locale.ROOT)
        return when {
            mime.startsWith("image/") || name.hasExtension("jpg", "jpeg", "png", "gif", "webp", "heic") -> "▧"
            mime.startsWith("video/") || name.hasExtension("mp4", "mkv", "webm", "mov", "avi", "3gp") -> "▶"
            mime.startsWith("audio/") || name.hasExtension("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus") -> "♫"
            mime == "application/pdf" || name.endsWith(".pdf") -> "PDF"
            name.endsWith(".apk") -> "APK"
            name.hasExtension("zip", "rar", "7z", "tar", "gz") -> "ZIP"
            name.hasExtension("doc", "docx", "odt") -> "DOC"
            name.hasExtension("xls", "xlsx", "csv") -> "XLS"
            name.hasExtension("ppt", "pptx") -> "PPT"
            else -> "▤"
        }
    }

    private fun String.hasExtension(vararg extensions: String): Boolean {
        val dot = lastIndexOf('.')
        return dot >= 0 && dot < lastIndex && substring(dot + 1) in extensions
    }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    class EntryHolder(
        itemView: View,
        val thumbnail: ImageView,
        val fallback: TextView,
        val name: TextView,
        val details: TextView,
        val menu: TextView
    ) : RecyclerView.ViewHolder(itemView) {
        var boundUri: Uri? = null
    }

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
        private val thumbnailExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "files-media-thumbnail").apply { isDaemon = true }
        }
    }
}
