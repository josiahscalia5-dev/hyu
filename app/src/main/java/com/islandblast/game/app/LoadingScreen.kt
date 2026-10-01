package com.islandblast.game.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.islandblast.game.ui.UiKit
import kotlin.math.sin

/** Shown for the moment a screen's art is decoded off the UI thread: "Level 5 · Temple Chase". */
class LoadingScreen(context: Context, ui: UiKit, title: String, subtitle: String) : Screen {
    private val card = LoadingView(context, ui, title, subtitle)
    override val view: View get() = card
    override fun start() = card.loop.start()
    override fun stop() = card.loop.stop()
    override fun onBack(): Boolean = true
}

@SuppressLint("ViewConstructor")
private class LoadingView(context: Context, private val ui: UiKit, private val title: String, private val subtitle: String) :
    View(context) {
    private var time = 0f
    val loop = FrameLoop { dt ->
        time += dt
        invalidate()
    }
    private val bg = Paint()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = UiKit.unit(width, height)
        bg.shader = LinearGradient(0f, 0f, 0f, h, 0xFF0D63B8.toInt(), 0xFF1AB0E0.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        val panel = RectF(w / 2f - 400f * k, h * 0.42f - 170f * k, w / 2f + 400f * k, h * 0.42f + 170f * k)
        ui.panel(c, panel, k)
        ui.text.white(c, title, w / 2f, panel.top + 95f * k, 52f * k, Color.WHITE, Paint.Align.CENTER, outline = 0.8f)
        if (subtitle.isNotEmpty()) ui.text.gold(c, subtitle, w / 2f, panel.top + 185f * k, 72f * k, Paint.Align.CENTER, 0f)
        for (i in 0 until 3) {
            val b = sin(time * 6f - i * 0.8f).coerceAtLeast(0f)
            dot.color = Color.argb((140 + 115 * b).toInt(), 255, 226, 122)
            c.drawCircle(w / 2f + (i - 1) * 46f * k, panel.bottom - 50f * k - b * 14f * k, 13f * k, dot)
        }
    }
}
