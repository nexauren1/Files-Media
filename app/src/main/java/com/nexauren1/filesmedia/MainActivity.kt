package com.nexauren1.filesmedia

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

    private fun renderHome() {
        pageKind = "home"
        pageTitle = "Files Media"
        buildToolbar(pageTitle, false)
        contentHost.removeAllViews()

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        scroll.addView(page)
        contentHost.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val intro = TextView(this).apply {
            text = "Os teus ficheiros, todos juntos"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            setPadding(dp(2), dp(4), dp(2), dp(14))
        }
        page.addView(intro)

        val searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 28)
            setPadding(dp(16), dp(2), dp(16), dp(2))
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { showSearchDialog() }
        }
        searchCard.addView(TextView(this).apply {
            text = "⌕"
            textSize = 28f
            setTextColor(primaryColor)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(38), dp(50)))
        searchCard.addView(TextView(this).apply {
            text = getString(R.string.search_hint)
            textSize = 15f
            setTextColor(secondaryColor)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(searchCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { bottomMargin = dp(22) })

        page.addView(sectionTitle("Categorias"))
        addCategoryGrid(page)

        page.addView(sectionTitle("Armazenamento"))
        page.addView(storageCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(22) })

        page.addView(sectionTitle("Recentes"))
        val recentRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(recentRows)
        val loading = TextView(this).apply {
            text = "A procurar ficheiros…"
            textSize = 14f
            setTextColor(secondaryColor)
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        recentRows.addView(loading)

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { repository.query("all") }
            if (pageKind != "home" || !isAddedOrFinishingSafe()) return@launch
            recentRows.removeAllViews()
            if (entries.isEmpty()) {
                recentRows.addView(emptyMessage("Permite o acesso a multimédia ou explora uma pasta para começar."))
            } else {
                entries.take(7).forEach { entry ->
                    recentRows.addView(createSimpleRow(entry, false) { openEntry(entry) })
                }
            }
        }
    }

    private fun isAddedOrFinishingSafe(): Boolean = !isFinishing && !isDestroyed

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
            val entries = withContext(Dispatchers.IO) { repository.query(category, searchQuery) }
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
        menu.menu.add(0, 6, 5, "Informações")
        menu.menu.add(0, 7, 6, "Eliminar")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openEntry(entry)
                2 -> shareEntry(entry)
                3 -> renameEntry(entry)
                4 -> chooseTransferDestination(entry, false)
                5 -> chooseTransferDestination(entry, true)
                6 -> showInformation(entry)
                7 -> deleteEntry(entry)
            }
            true
        }
        menu.show()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
