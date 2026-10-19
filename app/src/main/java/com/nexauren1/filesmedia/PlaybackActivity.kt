package com.nexauren1.filesmedia

import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class PlaybackActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        val uriText = intent.getStringExtra(EXTRA_URI) ?: intent.data?.toString()
        if (uriText.isNullOrBlank()) {
            finish()
            return
        }
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reproduzir"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(12), dp(4))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(52)))
        header.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ))

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        if (!isVideo) {
            root.addView(TextView(this).apply {
                text = "♫"
                textSize = 52f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(151, 164, 245))
                setPadding(0, dp(12), 0, dp(26))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(102)))
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        val token = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val controller = future.get()
                playerView.player = controller
                controller.setMediaItem(MediaItem.fromUri(uriText))
                controller.prepare()
                controller.playWhenReady = true
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "Não foi possível reproduzir este ficheiro.", android.widget.Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "com.nexauren1.filesmedia.extra.URI"
        const val EXTRA_TITLE = "com.nexauren1.filesmedia.extra.TITLE"
        const val EXTRA_IS_VIDEO = "com.nexauren1.filesmedia.extra.IS_VIDEO"
    }
}
