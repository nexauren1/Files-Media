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
import android.provider.OpenableColumns
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
import androidx.recyclerview.widget.GridLayoutManager
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
    private var pendingBatchDelete = false
    private var pendingBatchSafDeleteEntries: List<MediaEntry> = emptyList()
    private var pendingTransferEntries: List<MediaEntry> = emptyList()
    private var sortMode = "date"
    private var activeSearchQuery = ""

    private fun isGridModeEnabled(): Boolean =
        getSharedPreferences("files_media_preferences", MODE_PRIVATE)
            .getBoolean("grid_view_enabled", false)

    private fun setGridModeEnabled(enabled: Boolean) {
        getSharedPreferences("files_media_preferences", MODE_PRIVATE)
            .edit().putBoolean("grid_view_enabled", enabled).apply()
        reloadCurrentPage()
    }

    private fun fileLayoutManager(): RecyclerView.LayoutManager =
        if (isGridModeEnabled()) GridLayoutManager(this, 2) else LinearLayoutManager(this)

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The provider may return a temporary-only read grant.
        }
        val entry = readPdfEntry(uri)
        if (entry == null) {
            toast("Não foi possível aceder a este PDF.")
        } else {
            rememberRecentPdf(entry)
            openEntry(entry)
        }
    }

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
            pendingTransferEntries = emptyList()
            return@registerForActivityResult
        }
        val flags = (result.data?.flags ?: 0) and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some document providers return a transient-only grant. Continue with the current grant.
        }

        if (pendingTransferEntries.isNotEmpty()) {
            transferSelectedToFolder(uri)
        } else if (pendingTransferEntry != null) {
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
        if (pendingBatchDelete) {
            val safEntries = pendingBatchSafDeleteEntries
            pendingBatchDelete = false
            pendingBatchSafDeleteEntries = emptyList()
            if (result.resultCode == RESULT_OK) {
                lifecycleScope.launch {
                    val deletedSaf = deleteEntriesDirect(safEntries)
                    toast(if (safEntries.isEmpty() || deletedSaf == safEntries.size) {
                        "Eliminação concluída."
                    } else {
                        "Parte dos ficheiros foi eliminada. Alguns fornecedores recusaram o acesso."
                    })
                    currentAdapter?.clearSelection()
                    reloadCurrentPage()
                }
            } else {
                toast("A eliminação foi cancelada.")
            }
        } else if (result.resultCode == RESULT_OK) {
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
        menu.menu.add("Abrir um PDF…")
        menu.menu.add(if (isGridModeEnabled()) "Mudar para lista" else "Mudar para grelha")
        menu.menu.add("Ordenar: mais recentes")
        menu.menu.add("Ordenar: nome A–Z")
        menu.menu.add("Ordenar: maior tamanho")
        if (directoryStack.isNotEmpty()) menu.menu.add("Criar pasta nesta localização")
        menu.menu.add("Explorar armazenamento")
        menu.menu.add("Atualizar")
        menu.menu.add("Permissões")
        menu.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "Abrir um PDF…" -> openPdfPicker()
                "Mudar para grelha" -> setGridModeEnabled(true)
                "Mudar para lista" -> setGridModeEnabled(false)
                "Ordenar: mais recentes" -> { sortMode = "date"; reloadCurrentPage() }
                "Ordenar: nome A–Z" -> { sortMode = "name"; reloadCurrentPage() }
                "Ordenar: maior tamanho" -> { sortMode = "size"; reloadCurrentPage() }
                "Criar pasta nesta localização" -> createFolderInCurrentDirectory()
                "Explorar armazenamento" -> openStoragePicker(forcePicker = true)
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

        addBrandBanner(page)
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

    private fun addBrandBanner(parent: LinearLayout) {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(231, 238, 255), Color.rgb(248, 239, 255))
            ).apply { cornerRadius = dp(22).toFloat() }
            elevation = dp(1).toFloat()
        }
        banner.addView(FeatureIconView(this, "folder"), LinearLayout.LayoutParams(dp(54), dp(54)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        labels.addView(TextView(this).apply {
            text = "Files Media"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textColor)
            includeFontPadding = false
        })
        labels.addView(TextView(this).apply {
            text = "Tudo organizado num só lugar"
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, dp(5), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        banner.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(TextView(this).apply {
            text = "OFFLINE"
            textSize = 10f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(156, 83, 20))
            background = rounded(Color.rgb(255, 239, 215), 16)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            contentDescription = "Concebido para funcionar sem Internet"
        })
        parent.addView(banner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(82)
        ).apply { bottomMargin = dp(10) })
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
            val entries = withContext(Dispatchers.IO) {
                (getRecentPdfEntries() + repository.query("all"))
                    .distinctBy { it.uri.toString() }
                    .take(8)
            }
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
        val fallback = FeatureIconView(this, iconKeyForEntry(entry)).apply {
            contentDescription = "Pré-visualização de " + entry.name
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
        val favorites = collectionTile("star", "Marcados com estrela", "Ficheiros guardados") {
            openCategory("starred", "Marcados com estrela")
        }
        val largeFiles = collectionTile("large", "Ficheiros grandes", "Revê antes de eliminar") {
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
        tile.addView(FeatureIconView(this, icon), LinearLayout.LayoutParams(dp(42), dp(42)))
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
            "large"
        ) { openCategory("large", "Ficheiros grandes") })
        page.addView(cleanupActionCard(
            "Vídeos",
            "Os vídeos podem ocupar bastante espaço no dispositivo.",
            "video"
        ) { openCategory("videos", "Vídeos") })
        page.addView(cleanupActionCard(
            "Transferências",
            "Confirma se ainda precisas dos ficheiros recebidos.",
            "download"
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
        card.addView(FeatureIconView(this, icon), LinearLayout.LayoutParams(dp(52), dp(52)))
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
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        return card
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
                CategoryInfo("images", "Imagens", "image", "Fotos e imagens"),
                CategoryInfo("videos", "Vídeos", "video", "Os teus vídeos")
            ),
            listOf(
                CategoryInfo("audio", "Áudio", "audio", "Música e sons"),
                CategoryInfo("documents", "Documentos", "document", "PDF e documentos")
            ),
            listOf(
                CategoryInfo("downloads", "Transferências", "download", "Ficheiros recebidos"),
                CategoryInfo("all", "Todos os ficheiros", "folder", "Explorar conteúdos")
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
        val browse = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 18)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { openStoragePicker() }
        }
        browse.addView(FeatureIconView(this, "folder"), LinearLayout.LayoutParams(dp(42), dp(42)))
        browse.addView(TextView(this).apply {
            text = "Explorar armazenamento e pastas"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        browse.addView(TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
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
        upper.addView(FeatureIconView(this, info.icon), LinearLayout.LayoutParams(dp(44), dp(44)))
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
        val storageHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        storageHeading.addView(FeatureIconView(this, "storage"), LinearLayout.LayoutParams(dp(44), dp(44)))
        storageHeading.addView(TextView(this).apply {
            text = "Armazenamento interno"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(storageHeading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))
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

    private fun openPdfPicker() {
        pdfPicker.launch(arrayOf("application/pdf"))
    }

    private fun readPdfEntry(uri: Uri): MediaEntry? {
        val uriName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
        var name = uriName?.takeIf { it.endsWith(".pdf", ignoreCase = true) } ?: "Documento.pdf"
        var size = 0L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    }
                }
            }
        } catch (_: Exception) {
            // Some document providers omit OpenableColumns; continue with URI/MIME fallbacks.
        }
        val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        val isPdf = mime.equals("application/pdf", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true) ||
            uriName?.endsWith(".pdf", ignoreCase = true) == true
        if (!isPdf) return null
        if (!name.endsWith(".pdf", ignoreCase = true) && mime.equals("application/pdf", ignoreCase = true)) {
            name += ".pdf"
        }
        return MediaEntry(
            uri = uri,
            name = name,
            mimeType = "application/pdf",
            size = size,
            modified = System.currentTimeMillis() / 1000L,
            source = EntrySource.SAF,
            document = DocumentFile.fromSingleUri(this, uri)
        )
    }

    private fun rememberRecentPdf(entry: MediaEntry) {
        if (entry.source != EntrySource.SAF) return
        val prefs = getSharedPreferences("files_media_preferences", MODE_PRIVATE)
        val existing = prefs.getString("recent_pdf_uris", "").orEmpty()
            .lines().filter { it.isNotBlank() && it != entry.uri.toString() }
        val updated = (listOf(entry.uri.toString()) + existing).take(40)
        prefs.edit().putString("recent_pdf_uris", updated.joinToString("\n")).apply()
    }

    private fun getRecentPdfEntries(searchQuery: String = ""): List<MediaEntry> {
        val uriList = getSharedPreferences("files_media_preferences", MODE_PRIVATE)
            .getString("recent_pdf_uris", "").orEmpty()
            .lines().filter { it.isNotBlank() }
        val needle = searchQuery.trim().lowercase(Locale.ROOT)
        return uriList.mapNotNull { value ->
            try { readPdfEntry(Uri.parse(value)) } catch (_: Exception) { null }
        }.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
    }

    private fun sortEntries(entries: List<MediaEntry>): List<MediaEntry> {
        return when (sortMode) {
            "name" -> entries.sortedWith(
                compareByDescending<MediaEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            "size" -> entries.sortedWith(
                compareByDescending<MediaEntry> { it.isDirectory }
                    .thenByDescending { it.size }
            )
            else -> entries.sortedWith(
                compareByDescending<MediaEntry> { it.isDirectory }
                    .thenByDescending { it.modified }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        }
    }

    private fun openCategory(category: String, title: String) {
        directoryStack.clear()
        activeSearchQuery = ""
        renderCategory(category, title)
    }

    private fun renderCategory(category: String, title: String, searchQuery: String = "") {
        pageKind = category
        pageTitle = title
        activeSearchQuery = searchQuery
        buildToolbar(title, true)
        contentHost.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (category == "documents" || category == "downloads") {
            val action = TextView(this).apply {
                text = if (category == "documents") "＋   Abrir PDF do dispositivo" else "＋   Escolher pasta de transferências"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(primaryColor)
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.WHITE, 16)
                setPadding(dp(18), 0, dp(18), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (category == "documents") openPdfPicker() else openStoragePicker()
                }
            }
            page.addView(action, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
            ).apply {
                setMargins(dp(16), dp(8), dp(16), dp(6))
            })
            if (category == "documents") {
                page.addView(TextView(this).apply {
                    text = "Os PDFs escolhidos ficam disponíveis em Recentes e nesta lista."
                    textSize = 12f
                    setTextColor(secondaryColor)
                    setPadding(dp(18), dp(2), dp(18), dp(6))
                })
            }
        }
        val selectionUi = createSelectionBar()
        page.addView(selectionUi.container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { setMargins(dp(12), dp(6), dp(12), dp(2)) })
        val frame = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = fileLayoutManager()
            clipToPadding = false
            setPadding(
                if (isGridModeEnabled()) dp(7) else dp(12),
                dp(4),
                if (isGridModeEnabled()) dp(7) else dp(12),
                dp(12)
            )
            adapter = FileListAdapter(
                this@MainActivity,
                { entry -> openEntry(entry) },
                { entry, anchor -> showEntryMenu(entry, anchor) },
                initialGridMode = isGridModeEnabled(),
                onSelectionChanged = { selected, active ->
                    updateSelectionBar(selectionUi, selected, active)
                }
            )
        }
        currentAdapter = recycler.adapter as FileListAdapter
        val empty = emptyMessage("A carregar ficheiros…")
        frame.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        page.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        contentHost.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val sourceCategory = if (category == "starred" || category == "large") "all" else category
                val found = repository.query(sourceCategory, searchQuery)
                val combined = if (
                    category == "documents" || category == "starred" ||
                    category == "large" || category == "all"
                ) {
                    (getRecentPdfEntries(searchQuery) + found).distinctBy { it.uri.toString() }
                } else found
                val filtered = when (category) {
                    "starred" -> combined.filter { isFavorite(it) }
                    "large" -> combined.filter { it.size >= 100L * 1024L * 1024L }
                    else -> combined
                }
                when {
                    category == "large" -> filtered.sortedByDescending { it.size }
                    category == "documents" && sortMode == "date" -> filtered
                    else -> sortEntries(filtered)
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
            setText(activeSearchQuery)
            setSelection(text.length)
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

    private fun openStoragePicker(forcePicker: Boolean = false) {
        if (directoryStack.isNotEmpty() && !forcePicker) {
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
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val selectionUi = createSelectionBar()
        page.addView(selectionUi.container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { setMargins(dp(12), dp(6), dp(12), dp(2)) })
        val frame = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = fileLayoutManager()
            setPadding(
                if (isGridModeEnabled()) dp(7) else dp(12),
                dp(4),
                if (isGridModeEnabled()) dp(7) else dp(12),
                dp(12)
            )
            clipToPadding = false
            adapter = FileListAdapter(
                this@MainActivity,
                { entry ->
                    if (entry.isDirectory && entry.document != null) {
                        directoryStack.add(entry.document)
                        renderCurrentFolder()
                    } else {
                        openEntry(entry)
                    }
                },
                { entry, anchor -> showEntryMenu(entry, anchor) },
                initialGridMode = isGridModeEnabled(),
                onSelectionChanged = { selected, active ->
                    updateSelectionBar(selectionUi, selected, active)
                }
            )
        }
        currentAdapter = recycler.adapter as FileListAdapter
        val empty = emptyMessage("A abrir esta pasta…")
        frame.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        page.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        contentHost.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
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
                    }.let { sortEntries(it) }
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
                openMediaEntry(entry, mime.startsWith("video/"))
            }
            mime == "application/pdf" || entry.name.lowercase(Locale.ROOT).endsWith(".pdf") -> {
                rememberRecentPdf(entry)
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

    private fun openMediaEntry(entry: MediaEntry, isVideo: Boolean) {
        lifecycleScope.launch {
            val visible = currentVisibleEntries.filter { candidate ->
                !candidate.isDirectory && (candidate.mimeType ?: guessMime(candidate.name))
                    .startsWith(if (isVideo) "video/" else "audio/")
            }
            val indexed = withContext(Dispatchers.IO) {
                repository.query(if (isVideo) "videos" else "audio")
            }
            val queue = (indexed + visible + entry)
                .filter { candidate ->
                    !candidate.isDirectory && (candidate.mimeType ?: guessMime(candidate.name))
                        .startsWith(if (isVideo) "video/" else "audio/")
                }
                .distinctBy { it.uri.toString() }
            val selectedIndex = queue.indexOfFirst { it.uri == entry.uri }.coerceAtLeast(0)
            startActivity(Intent(this@MainActivity, PlaybackActivity::class.java).apply {
                putExtra(PlaybackActivity.EXTRA_URI, entry.uri.toString())
                putExtra(PlaybackActivity.EXTRA_TITLE, entry.name)
                putExtra(PlaybackActivity.EXTRA_IS_VIDEO, isVideo)
                putStringArrayListExtra(
                    PlaybackActivity.EXTRA_QUEUE_URIS,
                    ArrayList(queue.map { it.uri.toString() })
                )
                putStringArrayListExtra(
                    PlaybackActivity.EXTRA_QUEUE_TITLES,
                    ArrayList(queue.map { it.name })
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = entry.uri
            })
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

    private data class SelectionBarUi(
        val container: LinearLayout,
        val count: TextView
    )

    private fun createSelectionBar(): SelectionBarUi {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = rounded(Color.rgb(228, 232, 255), 16)
            visibility = View.GONE
        }
        val count = TextView(this).apply {
            text = "0 selecionados"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryColor)
            maxLines = 1
        }
        bar.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(selectionActionButton("↗", "Partilhar selecionados") {
            shareSelectedEntries(currentAdapter?.selectedEntries().orEmpty())
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(selectionActionButton("⋮", "Mais ações") { showSelectionActions(it) },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(selectionActionButton("✕", "Cancelar seleção") {
            currentAdapter?.clearSelection()
        }, LinearLayout.LayoutParams(dp(38), dp(44)))
        return SelectionBarUi(bar, count)
    }

    private fun selectionActionButton(symbol: String, label: String, action: (View) -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = if (symbol == "↗") 23f else 24f
            gravity = Gravity.CENTER
            setTextColor(primaryColor)
            contentDescription = label
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            setOnClickListener { action(this) }
        }

    private fun updateSelectionBar(
        ui: SelectionBarUi,
        selected: List<MediaEntry>,
        active: Boolean
    ) {
        ui.count.text = selected.size.toString() +
            if (selected.size == 1) " ficheiro selecionado" else " ficheiros selecionados"
        ui.container.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun showSelectionActions(anchor: View) {
        val adapter = currentAdapter ?: return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Selecionar tudo")
        menu.menu.add(0, 2, 1, "Partilhar selecionados")
        menu.menu.add(0, 3, 2, "Eliminar selecionados")
        menu.menu.add(0, 5, 3, "Copiar selecionados para…")
        menu.menu.add(0, 6, 4, "Mover selecionados para…")
        menu.menu.add(0, 4, 5, "Cancelar seleção")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> adapter.selectAll()
                2 -> shareSelectedEntries(adapter.selectedEntries())
                3 -> deleteSelectedEntries(adapter.selectedEntries())
                4 -> adapter.clearSelection()
                5 -> chooseBatchTransferDestination(adapter.selectedEntries(), false)
                6 -> chooseBatchTransferDestination(adapter.selectedEntries(), true)
            }
            true
        }
        menu.show()
    }

    private fun shareSelectedEntries(entries: List<MediaEntry>) {
        when (entries.size) {
            0 -> toast("Seleciona primeiro pelo menos um ficheiro.")
            1 -> shareEntry(entries.first())
            else -> {
                try {
                    val uris = ArrayList(entries.map { it.uri })
                    val mimeTypes = entries.map { it.mimeType ?: guessMime(it.name) }.distinct()
                    val clip = android.content.ClipData.newUri(contentResolver, entries.first().name, entries.first().uri)
                    entries.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it.uri)) }
                    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        clipData = clip
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Partilhar ficheiros"))
                } catch (_: Exception) {
                    toast("Não foi possível partilhar os ficheiros selecionados.")
                }
            }
        }
    }

    private fun deleteSelectedEntries(entries: List<MediaEntry>) {
        if (entries.isEmpty()) {
            toast("Seleciona primeiro pelo menos um ficheiro.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (entries.size == 1) "Eliminar ficheiro?" else "Eliminar ${entries.size} ficheiros?")
            .setMessage("Esta ação não pode ser anulada. O Android poderá pedir autorização para eliminar os ficheiros.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                val mediaEntries = entries.filter { it.source != EntrySource.SAF }
                val safEntries = entries.filter { it.source == EntrySource.SAF }
                if (mediaEntries.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val request = MediaStore.createDeleteRequest(
                            contentResolver, mediaEntries.map { it.uri }
                        )
                        pendingBatchDelete = true
                        pendingBatchSafDeleteEntries = safEntries
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (_: Exception) {
                        pendingBatchDelete = false
                        pendingBatchSafDeleteEntries = emptyList()
                        toast("O Android não autorizou a eliminação em lote.")
                    }
                } else {
                    lifecycleScope.launch {
                        val deleted = deleteEntriesDirect(entries)
                        toast(if (deleted == entries.size) {
                            "Eliminação concluída: $deleted ficheiros."
                        } else {
                            "Eliminados $deleted de ${entries.size} ficheiros."
                        })
                        currentAdapter?.clearSelection()
                        reloadCurrentPage()
                    }
                }
            }
            .show()
    }

    private suspend fun deleteEntriesDirect(entries: List<MediaEntry>): Int =
        withContext(Dispatchers.IO) {
            entries.count { entry ->
                try {
                    if (entry.source == EntrySource.SAF) {
                        entry.document?.delete() == true
                    } else {
                        contentResolver.delete(entry.uri, null, null) > 0
                    }
                } catch (_: Exception) {
                    false
                }
            }
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

    private fun chooseBatchTransferDestination(entries: List<MediaEntry>, move: Boolean) {
        if (entries.isEmpty()) {
            toast("Seleciona primeiro pelo menos um ficheiro.")
            return
        }
        if (entries.any { it.isDirectory }) {
            toast("A cópia em lote suporta ficheiros. Retira as pastas da seleção.")
            return
        }
        pendingTransferEntries = entries
        pendingTransferEntry = null
        pendingMove = move
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        treePicker.launch(intent)
    }

    private fun chooseTransferDestination(entry: MediaEntry, move: Boolean) {
        pendingTransferEntries = emptyList()
        pendingTransferEntry = entry
        pendingMove = move
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        treePicker.launch(intent)
    }

    private fun transferSelectedToFolder(destinationUri: Uri) {
        val entries = pendingTransferEntries
        pendingTransferEntries = emptyList()
        val move = pendingMove
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val destination = try {
                    DocumentFile.fromTreeUri(this@MainActivity, destinationUri)
                } catch (_: Exception) {
                    null
                } ?: return@withContext emptyList<MediaEntry>() to entries.size

                val copiedEntries = ArrayList<MediaEntry>()
                var failedCount = 0
                entries.forEach { entry ->
                    var target: DocumentFile? = null
                    try {
                        val mime = entry.mimeType ?: guessMime(entry.name)
                        var targetName = entry.name
                        if (destination.findFile(targetName) != null) {
                            targetName = "Copy - " + entry.name
                        }
                        if (destination.findFile(targetName) != null) {
                            targetName = System.currentTimeMillis().toString() + " - " + entry.name
                        }
                        target = destination.createFile(mime, targetName)
                        val created = target ?: throw IllegalStateException("Não foi possível criar o destino.")
                        val inputStream = contentResolver.openInputStream(entry.uri)
                            ?: throw IllegalStateException("Não foi possível ler o original.")
                        val outputStream = contentResolver.openOutputStream(created.uri, "w")
                            ?: throw IllegalStateException("Não foi possível escrever no destino.")
                        inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }
                        copiedEntries.add(entry)
                    } catch (_: Exception) {
                        try { target?.delete() } catch (_: Exception) { }
                        failedCount++
                    }
                }
                copiedEntries to failedCount
            }
            val copied = result.first
            val failed = result.second
            if (copied.isEmpty()) {
                toast("Não foi possível copiar os ficheiros selecionados.")
            } else {
                toast("Copiados ${copied.size} ficheiros" +
                    if (failed > 0) "; falharam $failed." else ".")
                currentAdapter?.clearSelection()
                if (move) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Concluir movimentação?")
                        .setMessage("Os ficheiros foram copiados. Queres eliminar os originais que foram copiados com sucesso?")
                        .setNegativeButton("Manter originais", null)
                        .setPositiveButton("Eliminar originais") { _, _ ->
                            deleteSelectedEntries(copied)
                        }
                        .show()
                }
            }
            reloadCurrentPage()
        }
    }

    private fun createFolderInCurrentDirectory() {
        val directory = directoryStack.lastOrNull()
        if (directory == null) {
            toast("Escolhe primeiro uma pasta de destino.")
            return
        }
        val input = EditText(this).apply {
            hint = "Nome da pasta"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Criar pasta")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name == "." || name == ".." ||
                    name.contains("/") || name.contains("\\")
                ) {
                    toast("Introduz um nome de pasta válido.")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val created = withContext(Dispatchers.IO) {
                        try {
                            directory.createDirectory(name) != null
                        } catch (_: Exception) {
                            false
                        }
                    }
                    toast(if (created) "Pasta criada." else "Não foi possível criar a pasta nesta localização.")
                    if (created) renderCurrentFolder()
                }
            }
            .show()
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
            else -> renderCategory(pageKind, pageTitle, activeSearchQuery)
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

    private fun iconKeyForEntry(entry: MediaEntry): String {
        if (entry.isDirectory) return "folder"
        val mime = (entry.mimeType ?: guessMime(entry.name)).lowercase(Locale.ROOT)
        val name = entry.name.lowercase(Locale.ROOT)
        return when {
            mime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".png") || name.endsWith(".webp") -> "image"
            mime.startsWith("video/") || name.endsWith(".mp4") || name.endsWith(".mkv") -> "video"
            mime.startsWith("audio/") || name.endsWith(".mp3") || name.endsWith(".flac") -> "audio"
            mime == "application/pdf" || name.endsWith(".pdf") -> "document"
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> "large"
            name.endsWith(".apk") -> "apps"
            name.endsWith(".download") || name.endsWith(".part") -> "download"
            else -> "document"
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
