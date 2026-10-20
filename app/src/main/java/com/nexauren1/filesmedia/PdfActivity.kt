package com.nexauren1.filesmedia

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import android.text.InputType
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfActivity : AppCompatActivity() {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private var documentUri: Uri? = null
    private var progressPreferenceKey = ""
    private lateinit var pageImage: ZoomablePdfImageView
    private lateinit var pageLabel: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private var renderToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.rgb(247, 246, 252)
        window.navigationBarColor = Color.rgb(247, 246, 252)

        val uriText = intent.getStringExtra(EXTRA_URI) ?: intent.data?.toString()
        if (uriText.isNullOrBlank()) {
            showError("O endereço do PDF não está disponível.")
            return
        }
        val uri = Uri.parse(uriText)
        documentUri = uri
        progressPreferenceKey = "pdf_page_" + uriText.hashCode()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Leitor PDF"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 246, 252))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 33, 42))
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        header.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.rgb(32, 33, 42))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerAction("↗", "Partilhar PDF") { sharePdf() },
            LinearLayout.LayoutParams(dp(44), dp(48)))
        val more = headerAction("⋮", "Mais opções") { }
        more.setOnClickListener { showDocumentMenu(more) }
        header.addView(more, LinearLayout.LayoutParams(dp(40), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(60)
        ))

        val pageFrame = FrameLayout(this)
        pageImage = ZoomablePdfImageView(this).apply {
            setBackgroundColor(Color.rgb(232, 231, 239))
            contentDescription = "Página do documento PDF. Usa dois dedos para ampliar."
        }
        pageFrame.addView(pageImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(pageFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        previousButton = Button(this).apply {
            text = "‹ Anterior"
            isEnabled = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setTextColor(Color.rgb(80, 88, 174))
        }
        nextButton = Button(this).apply {
            text = "Seguinte ›"
            isEnabled = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(80, 88, 174))
            setTextColor(Color.WHITE)
        }
        pageLabel = TextView(this).apply {
            text = "— / —"
            textSize = 14f
            setTextColor(Color.rgb(32, 33, 42))
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { showPagePicker() }
            contentDescription = "Escolher página"
        }
        controls.addView(previousButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(pageLabel, LinearLayout.LayoutParams(dp(82), dp(48)))
        controls.addView(nextButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
        ))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        previousButton.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex--
                renderPage()
            }
        }
        nextButton.setOnClickListener {
            val count = renderer?.pageCount ?: 0
            if (pageIndex < count - 1) {
                pageIndex++
                renderPage()
            }
        }

        try {
            fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            val descriptor = fileDescriptor ?: throw IllegalStateException("Não foi possível ler este PDF.")
            renderer = PdfRenderer(descriptor)
            pageIndex = getSharedPreferences("pdf_progress", MODE_PRIVATE)
                .getInt(progressPreferenceKey, 0)
                .coerceIn(0, (renderer?.pageCount ?: 1) - 1)
            updatePageControls()
            renderPage()
        } catch (_: Exception) {
            showError("Não foi possível abrir este PDF. Tenta abrir a partir de uma pasta selecionada no armazenamento.")
        }
    }

    private fun renderPage() {
        val currentRenderer = renderer ?: return
        val index = pageIndex
        val token = ++renderToken
        updatePageControls()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    synchronized(currentRenderer) {
                        val page = currentRenderer.openPage(index)
                        page.use {
                            val availableWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(360)
                            var scale = availableWidth.toFloat() / page.width.toFloat()
                            val largestDimension = maxOf(page.width, page.height)
                            if (largestDimension * scale > 4096f) {
                                scale = 4096f / largestDimension.toFloat()
                            }
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            image.eraseColor(Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (token == renderToken && !isFinishing && !isDestroyed) {
                if (bitmap != null) {
                    pageImage.setImageBitmap(bitmap)
                    pageImage.fitPage()
                } else showError("Esta página não pôde ser renderizada.")
            } else {
                bitmap?.recycle()
            }
        }
    }

    private fun updatePageControls() {
        val count = renderer?.pageCount ?: 0
        pageLabel.text = if (count == 0) "— / —" else (pageIndex + 1).toString() + " / " + count
        previousButton.isEnabled = pageIndex > 0
        nextButton.isEnabled = pageIndex < count - 1
        if (count > 0 && progressPreferenceKey.isNotEmpty()) {
            getSharedPreferences("pdf_progress", MODE_PRIVATE)
                .edit().putInt(progressPreferenceKey, pageIndex.coerceIn(0, count - 1)).apply()
        }
    }

    private fun headerAction(symbol: String, label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = if (symbol == "↗") 23f else 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 33, 42))
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun showDocumentMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Ir para a página…")
        menu.menu.add(0, 2, 1, "Ajustar à página")
        menu.menu.add(0, 3, 2, "Partilhar PDF")
        menu.menu.add(0, 4, 3, "Abrir com outra aplicação")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showPagePicker()
                2 -> pageImage.fitPage()
                3 -> sharePdf()
                4 -> openWithExternalViewer()
            }
            true
        }
        menu.show()
    }

    private fun showPagePicker() {
        val count = renderer?.pageCount ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "1–$count"
            setText((pageIndex + 1).toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Ir para a página")
            .setMessage("Este documento tem $count páginas.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Abrir") { _, _ ->
                val requested = input.text.toString().toIntOrNull()
                if (requested == null || requested !in 1..count) {
                    Toast.makeText(this, "Introduz uma página entre 1 e $count.", Toast.LENGTH_SHORT).show()
                } else {
                    pageIndex = requested - 1
                    renderPage()
                }
            }
            .show()
    }

    private fun sharePdf() {
        val uri = documentUri ?: return
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Documento PDF", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Partilhar PDF"))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível partilhar este PDF.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWithExternalViewer() {
        val uri = documentUri ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Abrir PDF com"))
        } catch (_: Exception) {
            Toast.makeText(this, "Não existe outro leitor de PDF disponível.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (renderer == null) {
            val text = TextView(this).apply {
                this.text = message
                gravity = Gravity.CENTER
                setTextColor(Color.DKGRAY)
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            setContentView(text)
        }
    }

    override fun onDestroy() {
        renderToken++
        val currentRenderer = renderer
        if (currentRenderer != null) {
            try {
                synchronized(currentRenderer) { currentRenderer.close() }
            } catch (_: Exception) { }
        }
        try { fileDescriptor?.close() } catch (_: Exception) { }
        renderer = null
        fileDescriptor = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "com.nexauren1.filesmedia.pdf.URI"
        const val EXTRA_TITLE = "com.nexauren1.filesmedia.pdf.TITLE"
    }
}
