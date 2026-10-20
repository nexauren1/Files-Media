package com.nexauren1.filesmedia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight custom icon tile for Files Media.
 *
 * All icons are drawn with Canvas, so the app does not need a large icon-font or illustration
 * dependency. The pastel tile and contrasting line art keep categories recognizable at small
 * sizes and consistent across device manufacturers.
 */
class FeatureIconView(
    context: Context,
    private var kind: String
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val density = resources.displayMetrics.density

    init {
        contentDescription = when (kind) {
            "folder", "all" -> "Pasta"
            "image" -> "Imagens"
            "video" -> "Vídeos"
            "audio" -> "Áudio"
            "document", "pdf" -> "Documento PDF"
            "download" -> "Transferências"
            "star" -> "Favoritos"
            "large" -> "Ficheiros grandes"
            "storage" -> "Armazenamento"
            "clean" -> "Limpeza"
            "apps" -> "Aplicações"
            else -> "Ficheiro"
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setKind(newKind: String) {
        if (kind == newKind) return
        kind = newKind
        contentDescription = when (kind) {
            "folder", "all" -> "Pasta"
            "image" -> "Imagens"
            "video" -> "Vídeos"
            "audio" -> "Áudio"
            "document", "pdf" -> "Documento PDF"
            "download" -> "Transferências"
            "star" -> "Favoritos"
            "large" -> "Ficheiros grandes"
            "storage" -> "Armazenamento"
            "clean" -> "Limpeza"
            "apps" -> "Aplicações"
            else -> "Ficheiro"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (48f * density).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val s = side / 48f
        val palette = palette(kind)

        paint.style = Paint.Style.FILL
        paint.color = palette.first
        bounds.set(cx - side * 0.46f, cy - side * 0.46f, cx + side * 0.46f, cy + side * 0.46f)
        canvas.drawRoundRect(bounds, side * 0.25f, side * 0.25f, paint)

        paint.color = palette.second
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.1f * s
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val left = cx - 13f * s
        val right = cx + 13f * s
        val top = cy - 12f * s
        val bottom = cy + 12f * s

        when (kind) {
            "folder", "all" -> drawFolder(canvas, left, top, right, bottom, s)
            "image" -> drawImage(canvas, left, top, right, bottom, s)
            "video" -> drawVideo(canvas, left, top, right, bottom, s)
            "audio" -> drawAudio(canvas, cx, cy, s)
            "document", "pdf" -> drawDocument(canvas, left, top, right, bottom, s)
            "download" -> drawDownload(canvas, cx, cy, s)
            "star" -> drawStar(canvas, cx, cy, 14f * s, 6.4f * s)
            "large" -> drawStack(canvas, cx, cy, s)
            "storage" -> drawStorage(canvas, cx, cy, s)
            "clean" -> drawSparkle(canvas, cx, cy, s)
            "apps" -> drawApps(canvas, cx, cy, s)
            "media" -> {
                drawVideo(canvas, left, top, right, bottom, s)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx + 9f * s, cy + 10f * s, 4f * s, paint)
            }
            else -> drawDocument(canvas, left, top, right, bottom, s)
        }
    }

    private fun drawFolder(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, s: Float) {
        val path = Path().apply {
            moveTo(l, t + 3f * s)
            lineTo(l + 9f * s, t + 3f * s)
            lineTo(l + 13f * s, t + 7f * s)
            lineTo(r, t + 7f * s)
            lineTo(r - 2f * s, b - 2f * s)
            lineTo(l + 2f * s, b - 2f * s)
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawLine(l + 1f * s, t + 11f * s, r - 1f * s, t + 11f * s, paint)
    }

    private fun drawImage(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, s: Float) {
        bounds.set(l, t, r, b)
        canvas.drawRoundRect(bounds, 3f * s, 3f * s, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(r - 6f * s, t + 6f * s, 2.1f * s, paint)
        paint.style = Paint.Style.STROKE
        val hill = Path().apply {
            moveTo(l + 3f * s, b - 4f * s)
            lineTo(l + 10f * s, t + 11f * s)
            lineTo(l + 14f * s, t + 16f * s)
            lineTo(l + 18f * s, t + 12f * s)
            lineTo(r - 3f * s, b - 4f * s)
            close()
        }
        canvas.drawPath(hill, paint)
    }

    private fun drawVideo(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, s: Float) {
        bounds.set(l, t + 1f * s, r, b - 1f * s)
        canvas.drawRoundRect(bounds, 4f * s, 4f * s, paint)
        paint.style = Paint.Style.FILL
        val play = Path().apply {
            moveTo(cx(l, r) - 3f * s, t + 7f * s)
            lineTo(cx(l, r) + 6f * s, cy(t, b))
            lineTo(cx(l, r) - 3f * s, b - 7f * s)
            close()
        }
        canvas.drawPath(play, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun drawAudio(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val stemX = cx + 4f * s
        val topY = cy - 13f * s
        canvas.drawLine(stemX, topY, stemX, cy + 7f * s, paint)
        canvas.drawLine(stemX, topY + 1f * s, cx - 5f * s, topY + 4f * s, paint)
        canvas.drawLine(cx - 5f * s, topY + 4f * s, cx - 5f * s, cy + 5f * s, paint)
        paint.style = Paint.Style.FILL
        canvas.drawOval(cx - 11f * s, cy + 4f * s, cx - 1f * s, cy + 11f * s, paint)
        canvas.drawOval(cx - 1f * s, cy + 6f * s, cx + 9f * s, cy + 13f * s, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun drawDocument(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, s: Float) {
        val fold = 7f * s
        val path = Path().apply {
            moveTo(l + 2f * s, t)
            lineTo(r - fold, t)
            lineTo(r, t + fold)
            lineTo(r, b)
            lineTo(l + 2f * s, b)
            close()
            moveTo(r - fold, t)
            lineTo(r - fold, t + fold)
            lineTo(r, t + fold)
        }
        canvas.drawPath(path, paint)
        canvas.drawLine(l + 6f * s, t + 13f * s, r - 5f * s, t + 13f * s, paint)
        canvas.drawLine(l + 6f * s, t + 18f * s, r - 5f * s, t + 18f * s, paint)
    }

    private fun drawDownload(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.drawLine(cx, cy - 13f * s, cx, cy + 4f * s, paint)
        canvas.drawLine(cx - 7f * s, cy - 2f * s, cx, cy + 5f * s, paint)
        canvas.drawLine(cx + 7f * s, cy - 2f * s, cx, cy + 5f * s, paint)
        canvas.drawLine(cx - 13f * s, cy + 10f * s, cx - 13f * s, cy + 13f * s, paint)
        canvas.drawLine(cx - 13f * s, cy + 13f * s, cx + 13f * s, cy + 13f * s, paint)
        canvas.drawLine(cx + 13f * s, cy + 13f * s, cx + 13f * s, cy + 10f * s, paint)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float) {
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = (-90f + i * 36f) * Math.PI / 180.0
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawStack(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        bounds.set(cx - 10f * s, cy - 12f * s, cx + 10f * s, cy + 9f * s)
        canvas.drawRoundRect(bounds, 2.5f * s, 2.5f * s, paint)
        bounds.set(cx - 13f * s, cy - 7f * s, cx + 7f * s, cy + 14f * s)
        canvas.drawRoundRect(bounds, 2.5f * s, 2.5f * s, paint)
    }

    private fun drawStorage(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        bounds.set(cx - 13f * s, cy - 10f * s, cx + 13f * s, cy + 10f * s)
        canvas.drawRoundRect(bounds, 4f * s, 4f * s, paint)
        canvas.drawLine(cx - 8f * s, cy - 3f * s, cx + 8f * s, cy - 3f * s, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - 7f * s, cy + 4f * s, 1.8f * s, paint)
        canvas.drawCircle(cx - 1f * s, cy + 4f * s, 1.8f * s, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val big = Path().apply {
            moveTo(cx, cy - 13f * s)
            lineTo(cx + 3f * s, cy - 3f * s)
            lineTo(cx + 13f * s, cy)
            lineTo(cx + 3f * s, cy + 3f * s)
            lineTo(cx, cy + 13f * s)
            lineTo(cx - 3f * s, cy + 3f * s)
            lineTo(cx - 13f * s, cy)
            lineTo(cx - 3f * s, cy - 3f * s)
            close()
        }
        canvas.drawPath(big, paint)
        val smallX = cx + 11f * s
        val smallY = cy - 11f * s
        canvas.drawLine(smallX, smallY - 4f * s, smallX, smallY + 4f * s, paint)
        canvas.drawLine(smallX - 4f * s, smallY, smallX + 4f * s, smallY, paint)
    }

    private fun drawApps(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        for (row in -1..1) for (column in -1..1) {
            bounds.set(
                cx + column * 8f * s - 2.2f * s,
                cy + row * 8f * s - 2.2f * s,
                cx + column * 8f * s + 2.2f * s,
                cy + row * 8f * s + 2.2f * s
            )
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(bounds, 1f * s, 1f * s, paint)
        }
        paint.style = Paint.Style.STROKE
    }

    private fun palette(kind: String): Pair<Int, Int> = when (kind) {
        "image" -> Color.rgb(243, 232, 255) to Color.rgb(116, 67, 181)
        "video" -> Color.rgb(255, 233, 218) to Color.rgb(191, 82, 29)
        "audio" -> Color.rgb(232, 233, 255) to Color.rgb(79, 83, 183)
        "document", "pdf" -> Color.rgb(221, 245, 247) to Color.rgb(22, 117, 134)
        "download" -> Color.rgb(224, 247, 235) to Color.rgb(22, 126, 79)
        "star" -> Color.rgb(255, 244, 211) to Color.rgb(168, 113, 14)
        "large" -> Color.rgb(255, 232, 219) to Color.rgb(185, 84, 37)
        "storage" -> Color.rgb(224, 236, 255) to Color.rgb(47, 96, 190)
        "clean" -> Color.rgb(255, 241, 216) to Color.rgb(180, 98, 17)
        "apps" -> Color.rgb(229, 244, 221) to Color.rgb(60, 126, 44)
        else -> Color.rgb(229, 236, 255) to Color.rgb(61, 83, 174)
    }

    private fun cx(left: Float, right: Float) = (left + right) / 2f
    private fun cy(top: Float, bottom: Float) = (top + bottom) / 2f
}
