package com.nexauren1.filesmedia

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val pageBackground = Color.rgb(247, 246, 252)
    private val surfaceColor = Color.WHITE
    private val primaryColor = Color.rgb(80, 88, 174)
    private val textColor = Color.rgb(30, 31, 40)
    private val secondaryColor = Color.rgb(101, 102, 116)

    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var contentHost: FrameLayout
    private val repository by lazy { StorageRepository(contentResolver) }
    private var pageKind = "home"
    private var pageTitle = "Files Media"
    private var currentAdapter: FileListAdapter? = null
    private var currentVisibleEntries: List<MediaEntry> = emptyList()
    private val directoryStack = ArrayList<DocumentFile>()
    private var pendingTransferEntry: MediaEntry? = null
    private var pendingMove = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pageKind == "home") renderHome() else reloadCurrentPage()
    }

    private val treePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            pendingTransferEntry = null
            return@registerForActivityResult
        }
        val flags = (result.data?.flags ?: 0) and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some document providers return a transient-only grant. Continue with the current grant.
        }

        if (pendingTransferEntry != null) {
            transferToFolder(uri)
        } else {
            val rootFolder = DocumentFile.fromTreeUri(this, uri)
            if (rootFolder != null) {
                directoryStack.clear()
                directoryStack.add(rootFolder)
                renderCurrentFolder()
            } else {
                toast("Não foi possível abrir esta pasta.")
            }
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            toast("Ficheiro eliminado.")
        } else {
            toast("A eliminação foi cancelada.")
        }
        reloadCurrentPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildShell()
        renderHome()
        requestMediaPermissionsIfNeeded()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(pageBackground)
            minimumHeight = dp(64)
        }
        contentHost = FrameLayout(this).apply {
            setBackgroundColor(pageBackground)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
        ))
        root.addView(contentHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildToolbar(title: String, showBack: Boolean) {
        toolbar.removeAllViews()
        if (showBack) {
            toolbar.addView(actionText("‹", 34f) { goBack() },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(actionText("⌕", 31f) { showSearchDialog() },
            LinearLayout.LayoutParams(dp(44), dp(48)))
        val menuButton = actionText("⋮", 28f) { }
        menuButton.setOnClickListener { showOverflowMenu(menuButton) }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(40), dp(48)))
    }

    private fun actionText(symbol: String, size: Float, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(textColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Explorar armazenamento")
        menu.menu.add("Atualizar")
        menu.menu.add("Permissões")
        menu.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "Explorar armazenamento" -> openStoragePicker()
                "Atualizar" -> reloadCurrentPage()
                "Permissões" -> requestMediaPermissionsIfNeeded(force = true)
            }
            true
        }
        menu.show()
    }

    private fun buildHomeToolbar() {
        toolbar.removeAllViews()
        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 30)
            setPadding(dp(8), 0, dp(8), 0)
            elevation = dp(1).toFloat()
        }
        searchPill.addView(actionText("☰", 22f) { showNavigationDrawer() },
            LinearLayout.LayoutParams(dp(42), dp(48)))
        val prompt = TextView(this).apply {
            text = "Pesquisar ficheiros"
            textSize = 16f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSearchDialog() }
        }
        searchPill.addView(prompt, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchPill.addView(actionText("⌕", 30f) { showSearchDialog() },
            LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbar.addView(searchPill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
            topMargin = dp(3)
            bottomMargin = dp(3)
        })
    }

    private fun renderHome() {
        pageKind = "home"
        pageTitle = "Files Media"
        buildHomeToolbar()
        contentHost.removeAllViews()

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(28))
        }
        scroll.addView(page)
        contentHost.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        addRecentCarousel(page)

        page.addView(sectionTitle("Categorias"))
        addCategoryGrid(page)

        page.addView(sectionTitle("Coleções"))
        addCollectionSection(page)

        page.addView(sectionTitle("Todo o armazenamento"))
        page.addView(storageCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        page.addView(TextView(this).apply {
            text = "Os teus ficheiros permanecem no dispositivo."
            textSize = 12f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        })
    }

    private fun addRecentCarousel(parent: LinearLayout) {
        parent.addView(sectionTitle("Recentes"))
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scroller.addView(row)
        val placeholder = TextView(this).apply {
            text = "Os ficheiros recentes aparecerão aqui."
            textSize = 13f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 18)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        row.addView(placeholder, LinearLayout.LayoutParams(
            dp(248), dp(126)
        ).apply { rightMargin = dp(10) })
        parent.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { repository.query("all").take(8) }
            if (pageKind != "home" || isFinishing || isDestroyed) return@launch
            row.removeAllViews()
            if (entries.isEmpty()) {
                row.addView(TextView(this@MainActivity).apply {
                    text = "Permite o acesso a multimédia para mostrar os teus ficheiros recentes."
                    textSize = 13f
                    setTextColor(secondaryColor)
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.WHITE, 18)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }, LinearLayout.LayoutParams(
                    dp(248), dp(126)
                ).apply { rightMargin = dp(10) })
            } else {
                entries.forEach { entry ->
                    row.addView(createRecentCard(entry), LinearLayout.LayoutParams(
                        dp(142), dp(142)
                    ).apply { rightMargin = dp(10) })
                }
            }
        }
    }

    private fun createRecentCard(entry: MediaEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { openEntry(entry) }
        }
        val preview = FrameLayout(this).apply {
            background = rounded(Color.rgb(235, 234, 244), 13)
        }
        val image = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        preview.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val fallback = TextView(this).apply {
            text = iconFor(entry)
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
        }
        preview.addView(fallback, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
        card.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(76)
        ))
        card.addView(TextView(this).apply {
            text = entry.name
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(2), dp(6), dp(2), 0)
        })
        card.addView(TextView(this).apply {
            text = if (entry.isDirectory) "Pasta" else StorageRepository.formatSize(entry.size)
            textSize = 11f
            setTextColor(secondaryColor)
            setPadding(dp(2), dp(3), dp(2), 0)
            maxLines = 1
        })

        val mime = (entry.mimeType ?: "").lowercase(Locale.ROOT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (mime.startsWith("image/") || mime.startsWith("video/"))
        ) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.loadThumbnail(
                            entry.uri,
                            android.util.Size(dp(132), dp(76)),
                            android.os.CancellationSignal()
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bitmap != null && !isDestroyed) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    fallback.visibility = View.GONE
                }
            }
        }
        return card
    }

    private fun addCollectionSection(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val favorites = collectionTile("☆", "Marcados com estrela", "Ficheiros guardados") {
            openCategory("starred", "Marcados com estrela")
        }
        val largeFiles = collectionTile("▤", "Ficheiros grandes", "Revê antes de eliminar") {
            openCategory("large", "Ficheiros grandes")
        }
        row.addView(favorites, LinearLayout.LayoutParams(0, dp(94), 1f).apply {
            rightMargin = dp(6)
        })
        row.addView(largeFiles, LinearLayout.LayoutParams(0, dp(94), 1f).apply {
            leftMargin = dp(6)
        })
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(22) })
    }

    private fun collectionTile(
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        tile.addView(TextView(this).apply {
            text = icon
            textSize = 26f
            setTextColor(primaryColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), dp(50)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(secondaryColor)
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        tile.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return tile
    }

    private fun showNavigationDrawer() {
        val drawer = android.app.Dialog(this)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(135, 0, 0, 0))
            setOnClickListener { drawer.dismiss() }
        }
        val panelWidth = minOf(dp(330), (resources.displayMetrics.widthPixels * 0.84f).toInt())
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            setPadding(dp(22), dp(42), dp(16), dp(20))
            isClickable = true
            isFocusable = true
        }
        overlay.addView(panel, FrameLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
        ))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        header.addView(TextView(this).apply {
            text = "📁"
            textSize = 34f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(52), dp(66)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "Files Media"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
        })
        brand.addView(TextView(this).apply {
            text = "Ficheiros, música, vídeo e PDF"
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(brand)
        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(88)
        ))
        addDrawerItem(panel, "✦", "Limpar", "Sugestões para libertar espaço") {
            drawer.dismiss()
            renderCleanupPage()
        }
        addDrawerItem(panel, "◉", "Armazenamento", "Ver espaço utilizado") {
            drawer.dismiss()
            renderStoragePage()
        }
        addDrawerItem(panel, "⚙", "Definições", "Permissões e preferências") {
            drawer.dismiss()
            showSettingsDialog()
        }
        addDrawerItem(panel, "?", "Ajuda e comentários", "Como utilizar o Files Media") {
            drawer.dismiss()
            AlertDialog.Builder(this)
                .setTitle("Ajuda e comentários")
                .setMessage("Explora as categorias, abre um ficheiro para o reproduzir ou ler e usa o menu ⋮ para partilhar, copiar, mover, mudar o nome ou eliminar. Para pastas completas, escolhe “Explorar armazenamento”.")
                .setPositiveButton("Entendi", null)
                .show()
        }
        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(224, 222, 235))
        }
        panel.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(12); bottomMargin = dp(12) })
        addDrawerItem(panel, "i", "Sobre a aplicação", "Versão " + BuildConfig.VERSION_NAME) {
            drawer.dismiss()
            AlertDialog.Builder(this)
                .setTitle("Files Media")
                .setMessage("Uma aplicação gratuita para organizar ficheiros, reproduzir áudio e vídeo e abrir PDFs. A prioridade é ser útil, leve, privada e acessível.")
                .setPositiveButton("OK", null)
                .show()
        }
        addDrawerItem(panel, "♧", "Privacidade", "Os ficheiros ficam no teu dispositivo") {
            drawer.dismiss()
            AlertDialog.Builder(this)
                .setTitle("Privacidade")
                .setMessage("O Files Media foi concebido para funcionar localmente. O acesso a multimédia e pastas depende das permissões concedidas pelo utilizador. Esta versão não exige conta.")
                .setPositiveButton("OK", null)
                .show()
        }

        drawer.setContentView(overlay)
        drawer.setCanceledOnTouchOutside(true)
        drawer.show()
        drawer.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setGravity(Gravity.TOP or Gravity.START)
        }
    }

    private fun addDrawerItem(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(4), dp(6))
            background = selectableBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        item.addView(TextView(this).apply {
            text = icon
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
        }, LinearLayout.LayoutParams(dp(42), dp(54)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
        })
        item.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(66)
        ))
    }

    private fun showSettingsDialog() {
        val options = arrayOf("Atualizar lista de ficheiros", "Permissões de multimédia", "Sobre o Files Media")
        AlertDialog.Builder(this)
            .setTitle("Definições")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> reloadCurrentPage()
                    1 -> requestMediaPermissionsIfNeeded(force = true)
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Files Media")
                        .setMessage("Versão " + BuildConfig.VERSION_NAME + "\n\nSem conta obrigatória e pensado para funcionar localmente.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun renderCleanupPage() {
        pageKind = "clean"
        pageTitle = "Limpar"
        buildToolbar(pageTitle, true)
        contentHost.removeAllViews()
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        scroll.addView(page)
        contentHost.addView(scroll)
        page.addView(storageCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) })
        page.addView(sectionTitle("Sugestões para libertar espaço"))
        page.addView(cleanupActionCard(
            "Ficheiros grandes",
            "Revê vídeos e outros ficheiros grandes antes de os eliminar.",
            "▤"
        ) { openCategory("large", "Ficheiros grandes") })
        page.addView(cleanupActionCard(
            "Vídeos",
            "Os vídeos podem ocupar bastante espaço no dispositivo.",
            "▶"
        ) { openCategory("videos", "Vídeos") })
        page.addView(cleanupActionCard(
            "Transferências",
            "Confirma se ainda precisas dos ficheiros recebidos.",
            "↓"
        ) { openCategory("downloads", "Transferências") })
        page.addView(TextView(this).apply {
            text = "Nada será apagado automaticamente. Revê os ficheiros e confirma cada eliminação."
            textSize = 13f
            setTextColor(secondaryColor)
            setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    private fun cleanupActionCard(title: String, description: String, icon: String, action: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(14), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        card.addView(TextView(this).apply {
            text = icon
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
        }, LinearLayout.LayoutParams(dp(48), dp(54)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
        })
        labels.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, dp(4), 0, 0)
            maxLines = 2
        })
        card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply {
            text = "›"
            textSize = 30f
            setTextColor(secondaryColor)
        })
        return card.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(12)
        }.apply { }
    }

    private fun renderStoragePage() {
        pageKind = "storage"
        pageTitle = "Armazenamento"
        buildToolbar(pageTitle, true)
        contentHost.removeAllViews()
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        scroll.addView(page)
        contentHost.addView(scroll)

        var total = 0L
        var free = 0L
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            total = stat.totalBytes
            free = stat.availableBytes
        } catch (_: Exception) { }
        val used = (total - free).coerceAtLeast(0L)
        val fraction = if (total > 0L) used.toFloat() / total.toFloat() else 0f
        page.addView(StorageDonutView(this, fraction), LinearLayout.LayoutParams(
            dp(230), dp(230)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
            bottomMargin = dp(12)
        })
        val usage = TextView(this).apply {
            text = "Usado: " + StorageRepository.formatSize(used) + "     •     Total: " + StorageRepository.formatSize(total)
            textSize = 15f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        }
        page.addView(usage)
        page.addView(sectionTitle("Conteúdo multimédia"))
        val summaryRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val breakdown = listOf(
            Triple("Imagens", "images", Color.rgb(50, 112, 227)),
            Triple("Vídeos", "videos", Color.rgb(34, 181, 112)),
            Triple("Áudio", "audio", Color.rgb(144, 80, 217)),
            Triple("Documentos", "documents", Color.rgb(222, 73, 169))
        )
        val valueViews = LinkedHashMap<String, TextView>()
        breakdown.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = "●"
                textSize = 14f
                setTextColor(item.third)
            }, LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = item.first
                textSize = 14f
                setTextColor(secondaryColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val value = TextView(this).apply {
                text = "A calcular…"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(textColor)
            }
            valueViews[item.first] = value
            row.addView(value)
            summaryRows.addView(row)
        }
        page.addView(summaryRows)
        page.addView(TextView(this).apply {
            text = "Os tamanhos por categoria representam ficheiros acessíveis ao Files Media. A utilização de aplicações e do sistema é gerida pelo Android."
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(dp(4), dp(12), dp(4), 0)
        })

        lifecycleScope.launch {
            val totals = withContext(Dispatchers.IO) {
                breakdown.associate { item ->
                    item.first to repository.query(item.second).sumOf { file -> file.size.coerceAtLeast(0L) }
                }
            }
            if (pageKind != "storage" || isFinishing || isDestroyed) return@launch
            totals.forEach { (label, bytes) ->
                valueViews[label]?.text = StorageRepository.formatSize(bytes)
            }
        }
    }

    private fun addCategoryGrid(parent: LinearLayout) {
        val rows = listOf(
            listOf(
                CategoryInfo("images", "Imagens", "🖼", "Fotos e imagens"),
                CategoryInfo("videos", "Vídeos", "🎬", "Os teus vídeos")
            ),
            listOf(
                CategoryInfo("audio", "Áudio", "♫", "Música e sons"),
                CategoryInfo("documents", "Documentos", "📄", "PDF e documentos")
            ),
            listOf(
                CategoryInfo("downloads", "Transferências", "⬇", "Ficheiros recebidos"),
                CategoryInfo("all", "Todos os ficheiros", "📁", "Explorar conteúdos")
            )
        )
        rows.forEach { rowInfo ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowInfo.forEachIndexed { index, info ->
                val tile = categoryTile(info)
                val params = LinearLayout.LayoutParams(0, dp(104), 1f)
                if (index == 0) params.marginEnd = dp(8) else params.marginStart = dp(8)
                params.bottomMargin = dp(12)
                row.addView(tile, params)
            }
            parent.addView(row)
        }
        val browse = TextView(this).apply {
            text = "📂   Explorar armazenamento e pastas"
            textSize = 15f
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { openStoragePicker() }
        }
        parent.addView(browse, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { bottomMargin = dp(22) })
    }

    private data class CategoryInfo(
        val key: String,
        val title: String,
        val icon: String,
        val subtitle: String
    )

    private fun categoryTile(info: CategoryInfo): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 20)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { openCategory(info.key, info.title) }
        }
        val upper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        upper.addView(TextView(this).apply {
            text = info.icon
            textSize = if (info.icon == "♫") 26f else 23f
            setTextColor(primaryColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(38), dp(40)))
        upper.addView(TextView(this).apply {
            text = info.title
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tile.addView(upper)
        tile.addView(TextView(this).apply {
            text = info.subtitle
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(dp(2), dp(4), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return tile
    }

    private fun storageCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 20)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val total: Long
        val free: Long
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            total = stat.totalBytes
            free = stat.availableBytes
        } catch (_: Exception) {
            card.addView(TextView(this).apply {
                text = "O espaço de armazenamento não está disponível."
                textSize = 14f
                setTextColor(secondaryColor)
            })
            return card
        }
        val used = (total - free).coerceAtLeast(0L)
        card.addView(TextView(this).apply {
            text = "Armazenamento interno"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
        })
        card.addView(TextView(this).apply {
            text = StorageRepository.formatSize(used) + " utilizados de " + StorageRepository.formatSize(total)
            textSize = 13f
            setTextColor(secondaryColor)
            setPadding(0, dp(7), 0, dp(9))
        })
        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = if (total > 0L) ((used.toDouble() / total.toDouble()) * 100.0).toInt() else 0
            progressTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(233, 232, 242))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { renderStoragePage() }
        return card
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(textColor)
        setPadding(dp(2), dp(8), dp(2), dp(12))
    }

    private fun openCategory(category: String, title: String) {
        directoryStack.clear()
        renderCategory(category, title)
    }

    private fun renderCategory(category: String, title: String, searchQuery: String = "") {
        pageKind = category
        pageTitle = title
        buildToolbar(title, true)
        contentHost.removeAllViews()
        val frame = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(12))
            adapter = FileListAdapter(this@MainActivity, { entry -> openEntry(entry) },
                { entry, anchor -> showEntryMenu(entry, anchor) })
        }
        currentAdapter = recycler.adapter as FileListAdapter
        val empty = emptyMessage("A carregar ficheiros…")
        frame.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        contentHost.addView(frame)
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val sourceCategory = if (category == "starred" || category == "large") "all" else category
                val found = repository.query(sourceCategory, searchQuery)
                when (category) {
                    "starred" -> found.filter { isFavorite(it) }
                    "large" -> found.filter { it.size >= 100L * 1024L * 1024L }
                        .sortedByDescending { it.size }
                    else -> found
                }
            }
            if (pageKind != category || isFinishing || isDestroyed) return@launch
            currentVisibleEntries = entries
            currentAdapter?.submit(entries)
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (entries.isEmpty()) {
                if (!hasAnyMediaPermission()) "Concede permissão para mostrar os teus ficheiros ou usa Explorar armazenamento no menu."
                else "Ainda não foram encontrados ficheiros nesta categoria."
            } else ""
        }
    }

    private fun createSimpleRow(entry: MediaEntry, showMenu: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(6), dp(8))
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            setOnClickListener { onClick() }
        }
        val icon = TextView(this).apply {
            text = iconFor(entry)
            textSize = 24f
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(235, 234, 244), 14)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        labels.addView(TextView(this).apply {
            text = entry.name
            textSize = 14f
            setTextColor(textColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        val detail = if (entry.isDirectory) "Pasta" else StorageRepository.formatSize(entry.size)
        labels.addView(TextView(this).apply {
            text = if (entry.modified > 0L) detail + " • " + StorageRepository.formatDate(entry.modified) else detail
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (showMenu) {
            row.addView(actionText("⋮", 24f) { }, LinearLayout.LayoutParams(dp(34), dp(44)))
        }
        row.minimumHeight = dp(70)
        return row
    }

    private fun emptyMessage(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(secondaryColor)
        setPadding(dp(28), dp(20), dp(28), dp(20))
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "Nome do ficheiro"
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Pesquisar ficheiros")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Pesquisar") { _, _ ->
                val term = input.text.toString().trim()
                directoryStack.clear()
                renderCategory("all", if (term.isEmpty()) "Todos os ficheiros" else "Resultados", term)
            }
            .show()
    }

    private fun openStoragePicker() {
        if (directoryStack.isNotEmpty()) {
            renderCurrentFolder()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        treePicker.launch(intent)
    }

    private fun renderCurrentFolder() {
        val directory = directoryStack.lastOrNull() ?: return
        pageKind = "browse"
        pageTitle = directory.name ?: "Armazenamento"
        buildToolbar(pageTitle, true)
        contentHost.removeAllViews()
        val frame = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(dp(12), dp(4), dp(12), dp(12))
            clipToPadding = false
            adapter = FileListAdapter(this@MainActivity, { entry ->
                if (entry.isDirectory && entry.document != null) {
                    directoryStack.add(entry.document)
                    renderCurrentFolder()
                } else {
                    openEntry(entry)
                }
            }, { entry, anchor -> showEntryMenu(entry, anchor) })
        }
        currentAdapter = recycler.adapter as FileListAdapter
        val empty = emptyMessage("A abrir esta pasta…")
        frame.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        contentHost.addView(frame)
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                try {
                    directory.listFiles().map { file ->
                        MediaEntry(
                            uri = file.uri,
                            name = file.name ?: "Ficheiro",
                            mimeType = file.type,
                            size = if (file.isFile) file.length() else 0L,
                            modified = file.lastModified() / 1000L,
                            source = EntrySource.SAF,
                            isDirectory = file.isDirectory,
                            document = file
                        )
                    }.sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (pageKind != "browse" || isFinishing || isDestroyed) return@launch
            currentVisibleEntries = entries
            currentAdapter?.submit(entries)
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (entries.isEmpty()) "Esta pasta está vazia ou o acesso foi recusado." else ""
        }
    }

    private fun goBack() {
        if (directoryStack.isNotEmpty()) {
            if (directoryStack.size > 1) {
                directoryStack.removeAt(directoryStack.lastIndex)
                renderCurrentFolder()
            } else {
                directoryStack.clear()
                renderHome()
            }
            return
        }
        if (pageKind != "home") {
            renderHome()
        } else {
            super.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun openEntry(entry: MediaEntry) {
        if (entry.isDirectory && entry.document != null) {
            directoryStack.add(entry.document)
            renderCurrentFolder()
            return
        }
        val mime = (entry.mimeType ?: guessMime(entry.name)).lowercase(Locale.ROOT)
        when {
            mime.startsWith("audio/") || mime.startsWith("video/") -> {
                startActivity(Intent(this, PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_URI, entry.uri.toString())
                    putExtra(PlaybackActivity.EXTRA_TITLE, entry.name)
                    putExtra(PlaybackActivity.EXTRA_IS_VIDEO, mime.startsWith("video/"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    data = entry.uri
                })
            }
            mime == "application/pdf" || entry.name.lowercase(Locale.ROOT).endsWith(".pdf") -> {
                startActivity(Intent(this, PdfActivity::class.java).apply {
                    data = entry.uri
                    putExtra(PdfActivity.EXTRA_URI, entry.uri.toString())
                    putExtra(PdfActivity.EXTRA_TITLE, entry.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            else -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(entry.uri, entry.mimeType ?: guessMime(entry.name))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Abrir ficheiro com"))
                } catch (_: Exception) {
                    toast("Não existe uma aplicação para abrir este ficheiro.")
                }
            }
        }
    }

    private fun showEntryMenu(entry: MediaEntry, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Abrir")
        menu.menu.add(0, 2, 1, "Partilhar")
        menu.menu.add(0, 3, 2, "Mudar nome")
        menu.menu.add(0, 4, 3, "Copiar para…")
        menu.menu.add(0, 5, 4, "Mover para…")
        menu.menu.add(0, 8, 5, if (isFavorite(entry)) "Remover dos favoritos" else "Adicionar aos favoritos")
        menu.menu.add(0, 6, 6, "Informações")
        menu.menu.add(0, 7, 7, "Eliminar")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openEntry(entry)
                2 -> shareEntry(entry)
                3 -> renameEntry(entry)
                4 -> chooseTransferDestination(entry, false)
                5 -> chooseTransferDestination(entry, true)
                6 -> showInformation(entry)
                7 -> deleteEntry(entry)
                8 -> toggleFavorite(entry)
            }
            true
        }
        menu.show()
    }

    private fun isFavorite(entry: MediaEntry): Boolean {
        val saved = getSharedPreferences("files_media_preferences", MODE_PRIVATE)
            .getStringSet("favorite_uris", emptySet())
        return saved?.contains(entry.uri.toString()) == true
    }

    private fun toggleFavorite(entry: MediaEntry) {
        val preferences = getSharedPreferences("files_media_preferences", MODE_PRIVATE)
        val saved = preferences.getStringSet("favorite_uris", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        val added = if (saved.contains(entry.uri.toString())) {
            saved.remove(entry.uri.toString())
            false
        } else {
            saved.add(entry.uri.toString())
            true
        }
        preferences.edit().putStringSet("favorite_uris", saved).apply()
        toast(if (added) "Adicionado aos favoritos." else "Removido dos favoritos.")
        if (pageKind == "starred") reloadCurrentPage()
    }

    private fun shareEntry(entry: MediaEntry) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = entry.mimeType ?: guessMime(entry.name)
                putExtra(Intent.EXTRA_STREAM, entry.uri)
                clipData = android.content.ClipData.newUri(contentResolver, entry.name, entry.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Partilhar ficheiro"))
        } catch (_: Exception) {
            toast("Não foi possível partilhar este ficheiro.")
        }
    }

    private fun showInformation(entry: MediaEntry) {
        val type = entry.mimeType ?: if (entry.isDirectory) "Pasta" else "Desconhecido"
        val date = StorageRepository.formatDate(entry.modified)
        val message = "Nome: " + entry.name + "\nTipo: " + type + "\nTamanho: " +
            (if (entry.isDirectory) "—" else StorageRepository.formatSize(entry.size)) +
            (if (date.isNotEmpty()) "\nModificado: " + date else "")
        AlertDialog.Builder(this).setTitle("Informações do ficheiro")
            .setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun renameEntry(entry: MediaEntry) {
        val input = EditText(this).apply {
            setText(entry.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Mudar nome").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == entry.name) return@setPositiveButton
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        try {
                            if (entry.source == EntrySource.SAF) {
                                entry.document?.renameTo(newName) == true
                            } else {
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                                }
                                contentResolver.update(entry.uri, values, null, null) > 0
                            }
                        } catch (_: Exception) {
                            false
                        }
                    }
                    toast(if (success) "Nome atualizado." else "Não foi possível mudar o nome. O Android pode pedir autorização.")
                    reloadCurrentPage()
                }
            }.show()
    }

    private fun chooseTransferDestination(entry: MediaEntry, move: Boolean) {
        pendingTransferEntry = entry
        pendingMove = move
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        treePicker.launch(intent)
    }

    private fun transferToFolder(destinationUri: Uri) {
        val entry = pendingTransferEntry ?: return
        val move = pendingMove
        pendingTransferEntry = null
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val destination = DocumentFile.fromTreeUri(this@MainActivity, destinationUri) ?: return@withContext false
                    val mime = entry.mimeType ?: guessMime(entry.name)
                    var output = destination.createFile(mime, entry.name)
                    if (output == null) {
                        output = destination.createFile(mime, "Copy - " + entry.name)
                    }
                    val target = output ?: return@withContext false
                    val inputStream = contentResolver.openInputStream(entry.uri) ?: return@withContext false
                    val outputStream = contentResolver.openOutputStream(target.uri) ?: return@withContext false
                    inputStream.use { input -> outputStream.use { out -> input.copyTo(out) } }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (success) {
                toast(if (move) "Cópia concluída. Confirma a eliminação do original." else "Ficheiro copiado.")
                if (move) deleteEntry(entry)
                reloadCurrentPage()
            } else {
                toast("Não foi possível copiar o ficheiro para essa pasta.")
            }
        }
    }

    private fun deleteEntry(entry: MediaEntry) {
        AlertDialog.Builder(this).setTitle("Eliminar ficheiro?")
            .setMessage("Esta ação pode não ser reversível. Queres continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                if (entry.source == EntrySource.SAF) {
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try { entry.document?.delete() == true } catch (_: Exception) { false }
                        }
                        toast(if (ok) "Ficheiro eliminado." else "Não foi possível eliminar o ficheiro.")
                        reloadCurrentPage()
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val request = MediaStore.createDeleteRequest(contentResolver, listOf(entry.uri))
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (_: Exception) {
                        toast("O Android não autorizou a eliminação.")
                    }
                } else {
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try { contentResolver.delete(entry.uri, null, null) > 0 } catch (_: Exception) { false }
                        }
                        toast(if (ok) "Ficheiro eliminado." else "Não foi possível eliminar o ficheiro.")
                        reloadCurrentPage()
                    }
                }
            }.show()
    }

    private fun reloadCurrentPage() {
        when {
            directoryStack.isNotEmpty() -> renderCurrentFolder()
            pageKind == "home" -> renderHome()
            pageKind == "clean" -> renderCleanupPage()
            pageKind == "storage" -> renderStoragePage()
            else -> renderCategory(pageKind, pageTitle)
        }
    }

    private fun requestMediaPermissionsIfNeeded(force: Boolean = false) {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyArray()
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && (force || missing.isNotEmpty())) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasAnyMediaPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun iconFor(entry: MediaEntry): String {
        if (entry.isDirectory) return "📁"
        val mime = (entry.mimeType ?: guessMime(entry.name)).lowercase(Locale.ROOT)
        return when {
            mime.startsWith("image/") -> "🖼"
            mime.startsWith("video/") -> "▶"
            mime.startsWith("audio/") -> "♫"
            mime == "application/pdf" -> "PDF"
            mime.contains("zip") || mime.contains("compressed") || entry.name.endsWith(".rar", true) -> "ZIP"
            entry.name.endsWith(".apk", true) -> "APK"
            else -> "📄"
        }
    }

    private fun guessMime(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "pdf" -> "application/pdf"
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v" -> "video/*"
            "mp3", "m4a", "aac", "ogg", "flac", "wav", "opus" -> "audio/*"
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/*"
            "txt", "csv" -> "text/plain"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        }
    }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return if (out.resourceId != 0) ContextCompat.getDrawable(this, out.resourceId) else null
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private class StorageDonutView(
        context: android.content.Context,
        private val usedFraction: Float
    ) : android.view.View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val stroke = 26f * density
            val size = minOf(width, height).toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = size / 2f - stroke / 2f - 3f * density
            bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(228, 227, 237)
            canvas.drawArc(bounds, -90f, 360f, false, paint)
            paint.color = Color.rgb(80, 88, 174)
            canvas.drawArc(bounds, -90f, 360f * usedFraction.coerceIn(0f, 1f), false, paint)

            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 34f * density
            paint.color = Color.rgb(30, 31, 40)
            canvas.drawText((usedFraction.coerceIn(0f, 1f) * 100f).toInt().toString() + "%",
                centerX, centerY + 10f * density, paint)
            paint.textSize = 12f * density
            paint.color = Color.rgb(101, 102, 116)
            canvas.drawText("utilizado", centerX, centerY + 32f * density, paint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
