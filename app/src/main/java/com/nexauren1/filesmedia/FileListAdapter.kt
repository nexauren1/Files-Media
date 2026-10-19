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

class FileListAdapter(
    private val context: Context,
    private val onOpen: (MediaEntry) -> Unit,
    private val onMenu: (MediaEntry, View) -> Unit
) : RecyclerView.Adapter<FileListAdapter.EntryHolder>() {

    private var entries: List<MediaEntry> = emptyList()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun submit(items: List<MediaEntry>) {
        entries = items.toList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
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
        val fallback = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(Color.rgb(80, 88, 174))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
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
        val name = TextView(context).apply {
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(32, 33, 42))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val details = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.rgb(105, 106, 119))
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
        }
        labels.addView(name)
        labels.addView(details)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val menu = TextView(context).apply {
            text = "⋮"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(75, 76, 88))
            isClickable = true
            isFocusable = true
        }
        row.addView(menu, LinearLayout.LayoutParams(dp(36), dp(50)))
        return EntryHolder(row, thumbnail, fallback, name, details, menu)
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
        if (!entry.isDirectory && Build.VERSION.SDK_INT >= 29 &&
            (mime.startsWith("image/") || mime.startsWith("video/"))
        ) {
            holder.fallback.text = if (mime.startsWith("video/")) "▶" else "🖼"
            thumbnailExecutor.execute {
                val bitmap: Bitmap? = try {
                    context.contentResolver.loadThumbnail(entry.uri, Size(dp(116), dp(116)), CancellationSignal())
                } catch (_: Exception) {
                    null
                }
                if (bitmap != null) {
                    mainHandler.post {
                        if (holder.thumbnail.tag == entry.uri) {
                            holder.thumbnail.setImageBitmap(bitmap)
                            holder.thumbnail.visibility = View.VISIBLE
                            holder.fallback.visibility = View.GONE
                        }
                    }
                }
            }
        }

        holder.itemView.setOnClickListener { onOpen(entry) }
        holder.menu.setOnClickListener { onMenu(entry, holder.menu) }
        holder.menu.contentDescription = "Ações para " + entry.name
        holder.itemView.contentDescription = entry.name
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
        return when {
            mime.startsWith("image/") -> "🖼"
            mime.startsWith("video/") -> "▶"
            mime.startsWith("audio/") -> "♫"
            mime == "application/pdf" || entry.name.endsWith(".pdf", true) -> "PDF"
            entry.name.endsWith(".apk", true) -> "APK"
            entry.name.endsWith(".zip", true) || entry.name.endsWith(".rar", true) -> "ZIP"
            else -> "📄"
        }
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
}
