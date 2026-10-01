package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shown while a level's artwork loads in the background: its design card and a spinner. */
@SuppressLint("ViewConstructor")
class LoadingView(context: Context, am: AssetManager, level: Int) : View(context) {
    private val opts = BitmapFactory.Options().apply { inScaled = false }
    private val bg = am.open("menu/background.jpg").use { BitmapFactory.decodeStream(it, null, opts)!! }
    private val text = GameText(Typeface.createFromAsset(am, "fonts/Fredoka-Bold.ttf"))
    private val title = if (level == 6) "STORM DODGE" else "COLOR SHIFT"
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFD040.toInt()
    }
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = max(w / bg.width, h / bg.height)
        c.drawBitmap(bg, null, RectF((w - bg.width * k) / 2f, (h - bg.height * k) / 2f, (w + bg.width * k) / 2f, (h + bg.height * k) / 2f), paint)
        c.drawColor(0x66000000)
        val u = min(w / 1080f, h / 2000f)
        text.white(c, "Level ${if (title == "STORM DODGE") 6 else 5}", w / 2f, h * 0.4f, 60f * u, align = Paint.Align.CENTER)
        text.gold(c, title, w / 2f, h * 0.4f + 110f * u, 110f * u)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val r = 60f * u
        arc.strokeWidth = 14f * u
        c.drawArc(w / 2f - r, h * 0.58f - r, w / 2f + r, h * 0.58f + r, t * 360f, 270f, false, arc)
        postInvalidateOnAnimation()
    }
}
