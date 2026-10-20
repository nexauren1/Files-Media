package com.nexauren1.filesmedia

import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
class PlaybackActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var isVideoMode = false
    private var currentUriText = ""
    private var currentTitle = ""
    private var seeking = false
    private var sleepTimer: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundColor = Color.rgb(247, 246, 252)
    private val primaryColor = Color.rgb(80, 88, 174)
    private val textColor = Color.rgb(30, 31, 40)
    private val secondaryColor = Color.rgb(101, 102, 116)

    private lateinit var headerTitleView: TextView
    private lateinit var audioTitle: TextView
    private lateinit var audioSubtitle: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var audioSeekBar: SeekBar
    private lateinit var positionLabel: TextView
    private lateinit var durationLabel: TextView

    private val subtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) attachExternalSubtitle(uri)
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isVideoMode && !isFinishing && !isDestroyed) {
                updateAudioProgress()
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateAudioProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateAudioProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val nextTitle = mediaItem?.mediaMetadata?.title?.toString() ?: currentTitle
            if (::headerTitleView.isInitialized) headerTitleView.text = nextTitle
            if (::audioTitle.isInitialized) audioTitle.text = nextTitle
            updateAudioProgress()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        currentUriText = intent.getStringExtra(EXTRA_URI) ?: intent.data?.toString().orEmpty()
        if (currentUriText.isBlank()) {
            finish()
            return
        }
        isVideoMode = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Reproduzir"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isVideoMode) Color.BLACK else backgroundColor)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        window.statusBarColor = if (isVideoMode) Color.BLACK else backgroundColor
        window.navigationBarColor = if (isVideoMode) Color.BLACK else backgroundColor
        window.decorView.systemUiVisibility = if (isVideoMode) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val headerTextColor = if (isVideoMode) Color.WHITE else textColor
        header.addView(headerAction("‹", "Voltar", headerTextColor) { finish() },
            LinearLayout.LayoutParams(dp(48), dp(52)))
        headerTitleView = TextView(this).apply {
            text = currentTitle
            textSize = 16f
            setTextColor(headerTextColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        header.addView(headerTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val more = headerAction("⋮", "Opções de reprodução", headerTextColor) { }
        more.setOnClickListener { showPlaybackMenu(more) }
        header.addView(more, LinearLayout.LayoutParams(dp(44), dp(52)))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(60)
        ))

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            keepScreenOn = true
            setShowSubtitleButton(true)
        }

        if (isVideoMode) {
            root.addView(playerView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        } else {
            root.addView(buildAudioPanel(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        val token = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val player = future.get()
                controller = player
                player.addListener(playerListener)
                if (isVideoMode) playerView.player = player

                val queueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS).orEmpty()
                val queueTitles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty()
                val uris = if (queueUris.isEmpty()) listOf(currentUriText)
                    else (queueUris + currentUriText).distinct()
                val items = uris.mapIndexed { index, uri ->
                    val fallbackTitle = queueTitles.getOrNull(index) ?: if (uri == currentUriText) currentTitle
                        else Uri.parse(uri).lastPathSegment ?: "Ficheiro"
                    MediaItem.Builder()
                        .setUri(Uri.parse(uri))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(fallbackTitle).build())
                        .build()
                }
                val selectedIndex = uris.indexOf(currentUriText).coerceAtLeast(0)
                player.setMediaItems(items, selectedIndex, C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
                if (!isVideoMode) {
                    updateAudioProgress()
                    handler.removeCallbacks(progressTicker)
                    handler.post(progressTicker)
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Não foi possível reproduzir este ficheiro.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildAudioPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(22))
        }

        val artwork = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(80, 88, 174), Color.rgb(120, 154, 222))
            ).apply { cornerRadius = dp(30).toFloat() }
            elevation = dp(5).toFloat()
        }
        artwork.addView(TextView(this).apply {
            text = "♫"
            textSize = 92f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            contentDescription = "Ilustração do leitor de música"
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        panel.addView(artwork, LinearLayout.LayoutParams(dp(224), dp(224)).apply {
            topMargin = dp(14)
            bottomMargin = dp(24)
        })

        audioTitle = TextView(this).apply {
            text = currentTitle
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        panel.addView(audioTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        audioSubtitle = TextView(this).apply {
            text = "Reprodução local · sem Internet"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(secondaryColor)
            setPadding(0, dp(7), 0, dp(12))
        }
        panel.addView(audioSubtitle)

        audioSeekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(primaryColor)
            thumbTintList = ColorStateList.valueOf(primaryColor)
            isClickable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) positionLabel.text = formatTime(
                        ((controller?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L) * progress) / 1000L
                    )
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val player = controller
                    val duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                    if (duration > 0L && seekBar != null) {
                        player?.seekTo((duration * seekBar.progress) / 1000L)
                    }
                    seeking = false
                    updateAudioProgress()
                }
            })
        }
        panel.addView(audioSeekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(4) })

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionLabel = TextView(this).apply {
            text = "0:00"
            textSize = 12f
            setTextColor(secondaryColor)
        }
        durationLabel = TextView(this).apply {
            text = "0:00"
            textSize = 12f
            setTextColor(secondaryColor)
            gravity = Gravity.END
        }
        times.addView(positionLabel, LinearLayout.LayoutParams(0, dp(24), 1f))
        times.addView(durationLabel, LinearLayout.LayoutParams(0, dp(24), 1f))
        panel.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        controls.addView(roundControl("↶", 52, Color.WHITE, textColor, "Retroceder 10 segundos") {
            controller?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        playPauseButton = roundControl("▶", 76, primaryColor, Color.WHITE, "Reproduzir ou pausar") {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
            updateAudioProgress()
        }
        controls.addView(playPauseButton, LinearLayout.LayoutParams(dp(82), dp(82)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        controls.addView(roundControl("↷", 52, Color.WHITE, textColor, "Avançar 10 segundos") {
            controller?.let { player ->
                val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration))
            }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        panel.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(96)
        ))
        panel.addView(TextView(this).apply {
            text = "Mais opções: velocidade, lista de reprodução, legendas e temporizador"
            textSize = 11f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        return panel
    }

    private fun roundControl(
        symbol: String,
        size: Int,
        background: Int,
        foreground: Int,
        description: String,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = symbol
        textSize = if (size > 60) 30f else 24f
        gravity = Gravity.CENTER
        setTextColor(foreground)
        this.background = GradientDrawable().apply {
            setColor(background)
            cornerRadius = dp(100).toFloat()
        }
        contentDescription = description
        isClickable = true
        isFocusable = true
        elevation = if (background == primaryColor) dp(3).toFloat() else 0f
        setOnClickListener { action() }
    }

    private fun headerAction(symbol: String, label: String, foreground: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun updateAudioProgress() {
        if (isVideoMode || !::audioSeekBar.isInitialized) return
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        if (!seeking) audioSeekBar.progress = if (duration > 0L) {
            ((position.toDouble() / duration.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
        } else 0
        positionLabel.text = formatTime(position)
        durationLabel.text = formatTime(duration)
        playPauseButton.text = if (player.isPlaying) "Ⅱ" else "▶"
        audioTitle.text = player.mediaMetadata.title?.toString() ?: currentTitle
        val currentUri = player.currentMediaItem?.localConfiguration?.uri
        audioSubtitle.text = currentUri?.lastPathSegment ?: "Reprodução local · sem Internet"
    }

    private fun showPlaybackMenu(anchor: View) {
        val player = controller
        val menu = PopupMenu(this, anchor)
        val speedMenu = menu.menu.addSubMenu("Velocidade de reprodução")
        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
            val item = speedMenu.add(String.format(Locale.getDefault(), "%.2f×", speed).replace(".00", ""))
            item.setOnMenuItemClickListener {
                controller?.setPlaybackParameters(PlaybackParameters(speed))
                Toast.makeText(this, "Velocidade: " + String.format(Locale.getDefault(), "%.2f×", speed).replace(".00", ""), Toast.LENGTH_SHORT).show()
                true
            }
        }
        menu.menu.add(0, 1, 1, if (player?.shuffleModeEnabled == true) "Desativar reprodução aleatória" else "Ativar reprodução aleatória")
        menu.menu.add(0, 2, 2, "Modo de repetição…")
        if (isVideoMode) menu.menu.add(0, 3, 3, "Legendas externas…")
        menu.menu.add(0, 4, 4, "Temporizador de suspensão…")
        menu.menu.add(0, 5, 5, "Informações do ficheiro")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    val current = controller ?: return@setOnMenuItemClickListener true
                    current.setShuffleModeEnabled(!current.shuffleModeEnabled)
                    Toast.makeText(this, if (current.shuffleModeEnabled) "Reprodução aleatória ativada." else "Reprodução aleatória desativada.", Toast.LENGTH_SHORT).show()
                }
                2 -> showRepeatOptions()
                3 -> subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
                4 -> showSleepTimerOptions()
                5 -> showMediaInformation()
            }
            true
        }
        menu.show()
    }

    private fun showRepeatOptions() {
        val options = arrayOf("Desativada", "Repetir lista", "Repetir ficheiro atual")
        val selected = when (controller?.repeatMode) {
            Player.REPEAT_MODE_ALL -> 1
            Player.REPEAT_MODE_ONE -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Modo de repetição")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                controller?.setRepeatMode(when (which) {
                    1 -> Player.REPEAT_MODE_ALL
                    2 -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                })
                dialog.dismiss()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showSleepTimerOptions() {
        val options = arrayOf("Desativar temporizador", "15 minutos", "30 minutos", "45 minutos", "60 minutos")
        AlertDialog.Builder(this)
            .setTitle("Parar reprodução após")
            .setItems(options) { _, which ->
                sleepTimer?.let { handler.removeCallbacks(it) }
                sleepTimer = null
                if (which == 0) {
                    Toast.makeText(this, "Temporizador desativado.", Toast.LENGTH_SHORT).show()
                } else {
                    val minutes = which * 15
                    val task = Runnable {
                        controller?.pause()
                        Toast.makeText(this, "Reprodução pausada pelo temporizador.", Toast.LENGTH_LONG).show()
                        sleepTimer = null
                    }
                    sleepTimer = task
                    handler.postDelayed(task, TimeUnit.MINUTES.toMillis(minutes.toLong()))
                    Toast.makeText(this, "A reprodução será pausada em $minutes minutos.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun attachExternalSubtitle(uri: Uri) {
        val player = controller ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers do not expose persistable access.
        }
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val mime = when (extension) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            else -> contentResolver.getType(uri) ?: MimeTypes.TEXT_VTT
        }
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage(Locale.getDefault().language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        val updatedItems = (0 until player.mediaItemCount).map { index ->
            val oldItem = player.getMediaItemAt(index)
            if (index == currentIndex) {
                MediaItem.Builder()
                    .setMediaId(oldItem.mediaId)
                    .setUri(oldItem.localConfiguration?.uri ?: Uri.parse(currentUriText))
                    .setMediaMetadata(oldItem.mediaMetadata)
                    .setSubtitleConfigurations(listOf(subtitle))
                    .build()
            } else oldItem
        }
        player.setMediaItems(updatedItems, currentIndex, position)
        player.prepare()
        player.playWhenReady = wasPlaying
        Toast.makeText(this, "Legenda adicionada.", Toast.LENGTH_SHORT).show()
    }

    private fun showMediaInformation() {
        val player = controller
        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: Uri.parse(currentUriText)
        val message = buildString {
            append("Nome: ")
            append(player?.mediaMetadata?.title?.toString() ?: currentTitle)
            append("\nEndereço: ")
            append(uri.lastPathSegment ?: uri.toString())
            if (player != null && player.duration > 0L && player.duration != C.TIME_UNSET) {
                append("\nDuração: ")
                append(formatTime(player.duration))
            }
            append("\n\nO suporte de formatos depende dos descodificadores disponíveis no Android e no Media3.")
        }
        AlertDialog.Builder(this).setTitle("Informações do ficheiro")
            .setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remainingSeconds = seconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        sleepTimer?.let { handler.removeCallbacks(it) }
        sleepTimer = null
        controller?.removeListener(playerListener)
        playerView.player = null
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "com.nexauren1.filesmedia.extra.URI"
        const val EXTRA_TITLE = "com.nexauren1.filesmedia.extra.TITLE"
        const val EXTRA_IS_VIDEO = "com.nexauren1.filesmedia.extra.IS_VIDEO"
        const val EXTRA_QUEUE_URIS = "com.nexauren1.filesmedia.extra.QUEUE_URIS"
        const val EXTRA_QUEUE_TITLES = "com.nexauren1.filesmedia.extra.QUEUE_TITLES"
        const val EXTRA_QUEUE_INDEX = "com.nexauren1.filesmedia.extra.QUEUE_INDEX"
    }
}
