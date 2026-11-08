package com.csm.jam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File

/** Renders the "Session Wrapped" recap as a shareable image and shares the played songs as text. */
object WrappedShare {

    data class Recap(
        val sessionName: String,
        val duration: String,
        val songsPlayed: Int,
        val topDj: String,
        val topArtist: String,
        val playedSongs: List<Pair<String, String>>, // title, added by
        val colorTop: Int,
        val colorBottom: Int
    )

    private const val W = 1080
    private const val H = 1350
    private const val ACCENT = 0xFF1DB954.toInt()

    fun shareImage(context: Context, recap: Recap) {
        val bitmap = render(recap)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "session_wrapped.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "✦ ${recap.sessionName} – Session Wrapped ✦")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Session Wrapped teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareSongList(context: Context, recap: Recap) {
        val text = buildString {
            append("✦ ${recap.sessionName} – gespielte Songs ✦\n\n")
            recap.playedSongs.forEachIndexed { i, (title, by) ->
                append("${i + 1}. $title")
                if (by.isNotBlank()) append("  ◇ $by")
                append('\n')
            }
            append("\n${recap.songsPlayed} Songs · ${recap.duration} · CSM Jam")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Songliste teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun render(r: Recap): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), r.colorTop, 0xFF0B0B0B.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        // soft accent glow
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(W * 0.85f, 120f, 520f, (ACCENT and 0x00FFFFFF) or 0x55000000, 0, Shader.TileMode.CLAMP)
        }
        c.drawCircle(W * 0.85f, 120f, 520f, glow)

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fun text(size: Float, color: Int, typeface: Typeface = bold, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; this.typeface = typeface; textAlign = align }

        val pad = 84f
        c.drawText("✦ SESSION WRAPPED ✦", pad, 170f, text(40f, ACCENT).apply { letterSpacing = 0.12f })
        c.drawText(ellipsize(r.sessionName, text(84f, 0xFFFFFFFF.toInt()), W - 2 * pad), pad, 280f, text(84f, 0xFFFFFFFF.toInt()))

        // Stat tiles
        val tiles = listOf("Dauer" to r.duration, "Songs" to r.songsPlayed.toString())
        val tileW = (W - 2 * pad - 32f) / 2
        tiles.forEachIndexed { i, (label, value) ->
            val left = pad + i * (tileW + 32f)
            val rect = RectF(left, 340f, left + tileW, 560f)
            c.drawRoundRect(rect, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF })
            c.drawText(label.uppercase(), left + 40f, 410f, text(30f, 0xFFAAAAAA.toInt()).apply { letterSpacing = 0.1f })
            c.drawText(ellipsize(value, text(76f, 0xFFFFFFFF.toInt()), tileW - 80f), left + 40f, 510f, text(76f, 0xFFFFFFFF.toInt()))
        }

        var y = 660f
        fun row(label: String, value: String, color: Int) {
            c.drawText(label.uppercase(), pad, y, text(30f, 0xFFAAAAAA.toInt()).apply { letterSpacing = 0.1f })
            c.drawText(ellipsize(value, text(56f, color), W - 2 * pad), pad, y + 70f, text(56f, color))
            y += 160f
        }
        row("Top DJ", r.topDj, 0xFFFFFFFF.toInt())
        row("Meist gespielt", r.topArtist, 0xFF64B5F6.toInt())

        if (r.playedSongs.isNotEmpty()) {
            c.drawText("ZULETZT GELAUFEN", pad, y, text(30f, 0xFFAAAAAA.toInt()).apply { letterSpacing = 0.1f })
            y += 64f
            val songPaint = text(40f, 0xFFFFFFFF.toInt(), Typeface.DEFAULT)
            r.playedSongs.takeLast(4).reversed().forEach { (title, _) ->
                c.drawText("◈  " + ellipsize(title, songPaint, W - 2 * pad - 60f), pad, y, songPaint)
                y += 60f
            }
        }

        c.drawText("CSM Jam · synchron im ganzen Raum", W / 2f, H - 70f, text(32f, 0x99FFFFFF.toInt(), Typeface.DEFAULT, Paint.Align.CENTER))
        return bmp
    }

    private fun ellipsize(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}
