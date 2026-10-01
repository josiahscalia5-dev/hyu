package com.islandblast.game

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

/** The Island Blast lettering styles (Fredoka Bold), shared by the level renderers. */
class GameText(private val face: Typeface) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    fun measure(s: String, size: Float): Float {
        paint.typeface = face
        paint.textSize = size
        paint.textScaleX = 1f
        return paint.measureText(s)
    }

    private fun setup(size: Float, align: Paint.Align, scaleX: Float) {
        paint.typeface = face
        paint.textSize = size
        paint.textAlign = align
        paint.textScaleX = scaleX
        paint.shader = null
        paint.strokeJoin = Paint.Join.ROUND
    }

    /** White with a dark outline and drop shadow (timer, labels). */
    fun white(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = 0xFFFFFFFF.toInt(),
              align: Paint.Align = Paint.Align.LEFT, scaleX: Float = 1f, alpha: Int = 255) {
        setup(size, align, scaleX)
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = size * 0.16f
        paint.color = 0x99000000.toInt(); paint.alpha = 0x99 * alpha / 255
        c.drawText(s, x, y + size * 0.05f, paint)
        paint.strokeWidth = size * 0.1f
        paint.color = 0xFF14080A.toInt(); paint.alpha = alpha
        c.drawText(s, x, y, paint)
        paint.strokeWidth = size * 0.025f
        paint.color = color; paint.alpha = alpha
        c.drawText(s, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.textScaleX = 1f
        paint.alpha = 255
    }

    /** Cream-to-gold digits with a dark outline (score). */
    fun cream(c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.LEFT) {
        setup(size, align, 1f)
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = size * 0.17f
        paint.color = 0xFF1A0C06.toInt()
        c.drawText(s, x, y + size * 0.04f, paint)
        paint.strokeWidth = size * 0.11f
        paint.color = 0xFF2E1608.toInt()
        c.drawText(s, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, y - size * 0.72f, 0f, y, intArrayOf(
            0xFFFFFDF0.toInt(), 0xFFFFF3B8.toInt(), 0xFFFFDC62.toInt(), 0xFFF3B83A.toInt(),
        ), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawText(s, x, y, paint)
        paint.shader = null
    }

    /** Yellow-to-orange fill, red rim, chunky brown outline (titles, combo, rewards). */
    fun gold(c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.CENTER,
             angle: Float = 0f, rim: Boolean = true, alpha: Int = 255) {
        c.save()
        if (angle != 0f) c.rotate(angle, x, y)
        setup(size, align, 1f)
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = size * 0.2f
        paint.color = 0xFF2E0E04.toInt(); paint.alpha = alpha
        c.drawText(s, x, y + size * 0.04f, paint)
        paint.strokeWidth = size * 0.16f
        paint.color = 0xFF4A1A08.toInt(); paint.alpha = alpha
        c.drawText(s, x, y, paint)
        if (rim) {
            paint.strokeWidth = size * 0.07f
            paint.color = 0xFFE0321A.toInt(); paint.alpha = alpha
            c.drawText(s, x, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, y - size * 0.72f, 0f, y, intArrayOf(
            0xFFFFFBC8.toInt(), 0xFFFFEA3C.toInt(), 0xFFFFD21C.toInt(), 0xFFFFAE08.toInt(),
        ), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        paint.alpha = alpha
        c.drawText(s, x, y, paint)
        paint.shader = null
        paint.alpha = 255
        c.restore()
    }

    /** Bright blue fill with a dark navy outline (the "DODGE" style). */
    fun blue(c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255) {
        setup(size, align, 1f)
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = size * 0.2f
        paint.color = 0xFF081436.toInt(); paint.alpha = alpha
        c.drawText(s, x, y + size * 0.04f, paint)
        paint.strokeWidth = size * 0.14f
        paint.color = 0xFF0E2A6E.toInt(); paint.alpha = alpha
        c.drawText(s, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, y - size * 0.72f, 0f, y, intArrayOf(
            0xFFB8F4FF.toInt(), 0xFF3CD2FF.toInt(), 0xFF1490F0.toInt(),
        ), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        paint.alpha = alpha
        c.drawText(s, x, y, paint)
        paint.shader = null
        paint.alpha = 255
    }
}
