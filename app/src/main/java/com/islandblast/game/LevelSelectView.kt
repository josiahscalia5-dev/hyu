package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Start screen: pick Level 5 (Color Shift) or Level 6 (Storm Dodge). */
@SuppressLint("ViewConstructor")
class LevelSelectView(context: Context, private val am: AssetManager, private val onPick: (Int) -> Unit) : View(context) {
    private class Card(val level: Int, val title: String, val subtitle: String, val thumb: Bitmap, val crop: Rect)

    private val opts = BitmapFactory.Options().apply { inScaled = false }
    private fun load(path: String) = am.open(path).use { BitmapFactory.decodeStream(it, null, opts)!! }

    // Thumbnails are the approved level designs (assets/menu, made from design/*.png).
    private val bg = load("menu/background.jpg")
    private val cards = listOf(
        load("menu/level5.jpg").let { Card(5, "Level 5", "Color Shift", it, Rect(0, it.height * 26 / 100, it.width, it.height * 56 / 100)) },
        load("menu/level6.jpg").let { Card(6, "Level 6", "Storm Dodge", it, Rect(0, it.height * 36 / 100, it.width, it.height * 66 / 100)) },
    )
    private val text = GameText(Typeface.createFromAsset(am, "fonts/Fredoka-Bold.ttf"))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rects = ArrayList<RectF>()
    private var pressed = -1

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = max(w / bg.width, h / bg.height)
        c.drawBitmap(bg, null, RectF((w - bg.width * k) / 2f, (h - bg.height * k) / 2f, (w + bg.width * k) / 2f, (h + bg.height * k) / 2f), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x33000000, 0xCC0A0618.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val u = min(w / 1080f, h / 2000f)
        text.gold(c, "ISLAND", w / 2f, h * 0.15f, 150f * u)
        text.blue(c, "BLAST", w / 2f, h * 0.15f + 140f * u, 150f * u)
        text.white(c, "Choose a level", w / 2f, h * 0.15f + 250f * u, 52f * u, align = Paint.Align.CENTER)

        rects.clear()
        val cw = w * 0.82f
        val ch = h * 0.24f
        var top = h * 0.34f
        for ((i, card) in cards.withIndex()) {
            val r = RectF((w - cw) / 2f, top, (w + cw) / 2f, top + ch)
            rects += r
            val s = if (pressed == i) 0.97f else 1f
            c.save()
            c.scale(s, s, r.centerX(), r.centerY())
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(r, 40f * u, 40f * u, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(card.thumb, card.crop, r, paint)
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0x00000000, 0xCC000000.toInt()), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(r, paint)
            paint.shader = null
            c.restore()
            stroke.color = 0xFFFFD040.toInt(); stroke.strokeWidth = 8f * u
            c.drawRoundRect(r, 40f * u, 40f * u, stroke)
            text.white(c, card.title, r.left + 50f * u, r.bottom - 120f * u, 56f * u)
            if (card.level == 6) {
                text.gold(c, "STORM", r.left + 50f * u, r.bottom - 40f * u, 84f * u, align = Paint.Align.LEFT)
                text.blue(c, "DODGE", r.left + 50f * u + text.measure("STORM ", 84f * u), r.bottom - 40f * u, 84f * u, align = Paint.Align.LEFT)
            } else {
                text.gold(c, card.subtitle.uppercase(), r.left + 50f * u, r.bottom - 40f * u, 84f * u, align = Paint.Align.LEFT)
            }
            c.restore()
            top += ch + h * 0.05f
        }
        text.white(c, "Tap a level to play", w / 2f, h * 0.93f, 42f * u, Color.WHITE, Paint.Align.CENTER, alpha = 200)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = rects.indexOfFirst { it.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1
                invalidate()
                if (p >= 0 && p == hit) onPick(cards[p].level)
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
