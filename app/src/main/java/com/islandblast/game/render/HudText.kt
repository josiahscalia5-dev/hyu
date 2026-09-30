package com.islandblast.game.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.islandblast.game.model.TextSpot

/**
 * The painted HUD lettering both levels share, drawn live in Fredoka Bold: white with a
 * dark outline (timer), cream-to-gold (score) and the chunky gold of "Combo x9".
 */
class HudText(private val typeface: Typeface) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** [outline] scales the dark outline and drop shadow (1 = the Level 5 weight). */
    fun white(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, scaleX: Float = 1f, outline: Float = 1f,
    ) {
        text.typeface = typeface
        text.textSize = size
        text.textScaleX = scaleX
        text.textAlign = align
        text.shader = null
        text.style = Paint.Style.FILL_AND_STROKE
        text.strokeJoin = Paint.Join.ROUND
        text.strokeWidth = size * 0.16f * outline
        text.color = 0x99000000.toInt()
        c.drawText(s, x, y + size * 0.05f * outline, text)
        text.strokeWidth = size * 0.1f * outline
        text.color = 0xFF14080A.toInt()
        c.drawText(s, x, y, text)
        text.strokeWidth = size * 0.025f
        text.color = color
        c.drawText(s, x, y, text)
        text.style = Paint.Style.FILL
        text.textScaleX = 1f
    }

    /** Score digits as the references paint them: cream-to-gold fill with a dark outline. */
    fun cream(c: Canvas, s: String, spot: TextSpot, outline: Float = 1f) {
        val size = spot.size
        text.typeface = typeface
        text.textSize = size
        text.textScaleX = spot.scaleX
        text.textAlign = Paint.Align.LEFT
        text.shader = null
        text.strokeJoin = Paint.Join.ROUND
        text.style = Paint.Style.FILL_AND_STROKE
        text.strokeWidth = size * 0.17f * outline
        text.color = 0xFF1A0C06.toInt()
        c.drawText(s, spot.x, spot.y + size * 0.04f * outline, text)
        text.strokeWidth = size * 0.11f * outline
        text.color = 0xFF2E1608.toInt()
        c.drawText(s, spot.x, spot.y, text)
        text.style = Paint.Style.FILL
        text.shader = LinearGradient(0f, spot.y - size * 0.72f, 0f, spot.y, intArrayOf(
            0xFFFFFDF0.toInt(), 0xFFFFF3B8.toInt(), 0xFFFFDC62.toInt(), 0xFFF3B83A.toInt(),
        ), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawText(s, spot.x, spot.y, text)
        text.shader = null
        text.textScaleX = 1f
    }

    /** Yellow-to-orange fill, thin red rim, chunky brown outline: the reference's "Combo x9" / "+30". */
    fun gold(
        c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align, angle: Float, scaleX: Float = 1f,
        rim: Boolean = true,
    ) {
        c.save()
        if (angle != 0f) c.rotate(angle, x, y)
        text.typeface = typeface
        text.textSize = size
        text.textScaleX = scaleX
        text.textAlign = align
        text.shader = null
        text.strokeJoin = Paint.Join.ROUND
        text.style = Paint.Style.FILL_AND_STROKE
        text.strokeWidth = size * 0.2f
        text.color = 0xFF2E0E04.toInt()
        c.drawText(s, x, y + size * 0.04f, text)
        text.strokeWidth = size * 0.16f
        text.color = 0xFF4A1A08.toInt()
        c.drawText(s, x, y, text)
        if (rim) {
            text.strokeWidth = size * 0.07f
            text.color = 0xFFE0321A.toInt()
            c.drawText(s, x, y, text)
        }
        text.style = Paint.Style.FILL
        text.shader = LinearGradient(0f, y - size * 0.72f, 0f, y, intArrayOf(
            0xFFFFFBC8.toInt(), 0xFFFFEA3C.toInt(), 0xFFFFD21C.toInt(), 0xFFFFAE08.toInt(),
        ), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawText(s, x, y, text)
        text.shader = null
        text.textScaleX = 1f
        c.restore()
    }

    fun measure(s: String, size: Float): Float {
        text.typeface = typeface
        text.textSize = size
        text.textAlign = Paint.Align.LEFT
        return text.measureText(s)
    }
}
