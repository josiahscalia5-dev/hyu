package com.islandblast.game.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.islandblast.game.render.HudText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Colours of a glossy button: light top, deep bottom, dark rim, the ledge under it. */
enum class ButtonStyle(val top: Int, val bottom: Int, val rim: Int, val ledge: Int, val text: Int) {
    GREEN(0xFF9BF23E.toInt(), 0xFF35B21A.toInt(), 0xFF185F0B.toInt(), 0xFF1F7A10.toInt(), 0xFF0F3D06.toInt()),
    BLUE(0xFF5CC8FF.toInt(), 0xFF1E74E8.toInt(), 0xFF0B2F78.toInt(), 0xFF123F9C.toInt(), 0xFF0A2560.toInt()),
    ORANGE(0xFFFFD45A.toInt(), 0xFFF28A12.toInt(), 0xFF7A3404.toInt(), 0xFFA9500A.toInt(), 0xFF5A2402.toInt()),
    GRAY(0xFFB9C4D6.toInt(), 0xFF6D7C96.toInt(), 0xFF2C3446.toInt(), 0xFF414B60.toInt(), 0xFF20263A.toInt()),
}

enum class Icon { BACK, PLAY, LOCK, PAUSE, RESTART, MAP }

/**
 * Drawing for the game's menus, in the style of the home screen: glossy buttons like
 * PLAY, navy panels like the top bar's pills, Fredoka lettering, the painted stars.
 * Sizes are in screen pixels; callers pass a unit [k] (screen width / 1080).
 */
class UiKit(am: AssetManager) {
    val fredoka: Typeface = Typeface.createFromAsset(am, "fonts/Fredoka-Bold.ttf")
    val text = HudText(fredoka)

    private val opts = BitmapFactory.Options().apply { inScaled = false }
    private fun bmp(am: AssetManager, name: String): Bitmap =
        (am.open(name).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Missing asset $name"))
            .apply { density = Bitmap.DENSITY_NONE }

    val starGold = bmp(am, "level5/star_gold.png")
    val starEmpty = bmp(am, "level5/star_empty.png")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()

    /** A glossy pill button with a white label; [pressed] sinks it onto its ledge. */
    fun button(c: Canvas, box: RectF, label: String, style: ButtonStyle, pressed: Boolean, k: Float, icon: Icon? = null) {
        val ledge = 9f * k
        val sink = if (pressed) ledge * 0.7f else 0f
        val rad = box.height() * 0.42f
        // Ledge and soft shadow.
        fill.shader = null
        fill.color = 0x55000000
        r.set(box.left + 2f * k, box.top + ledge + 6f * k, box.right - 2f * k, box.bottom + ledge + 6f * k)
        c.drawRoundRect(r, rad, rad, fill)
        fill.color = style.ledge
        r.set(box.left, box.top + ledge, box.right, box.bottom + ledge)
        c.drawRoundRect(r, rad, rad, fill)
        // Body.
        r.set(box.left, box.top + sink, box.right, box.bottom + sink)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, style.top, style.bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        // Gloss on the upper half.
        val g = RectF(r.left + r.height() * 0.18f, r.top + r.height() * 0.07f, r.right - r.height() * 0.18f,
            r.top + r.height() * 0.5f)
        fill.shader = LinearGradient(0f, g.top, 0f, g.bottom, 0x8CFFFFFF.toInt(), 0x10FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(g, g.height() / 2f, g.height() / 2f, fill)
        fill.shader = null
        stroke.color = style.rim
        stroke.strokeWidth = 5f * k
        c.drawRoundRect(r, rad, rad, stroke)
        // Label (and optional icon before it), white with a dark outline like "PLAY".
        val size = r.height() * 0.46f
        val tw = text.measure(label, size)
        val iconW = if (icon != null) size * 1.1f else 0f
        val x0 = r.centerX() - (tw + iconW) / 2f
        if (icon != null) drawIcon(c, icon, x0 + iconW * 0.4f, r.centerY(), size * 0.5f, Color.WHITE, style.text, k)
        text.white(c, label, x0 + iconW, r.centerY() + size * 0.36f, size, Color.WHITE, Paint.Align.LEFT, outline = 0.9f)
    }

    /** A round glossy button holding an icon (back, pause...). */
    fun roundButton(c: Canvas, cx: Float, cy: Float, radius: Float, style: ButtonStyle, icon: Icon?, pressed: Boolean, k: Float) {
        val ledge = radius * 0.12f
        val sink = if (pressed) ledge * 0.7f else 0f
        fill.shader = null
        fill.color = 0x55000000
        c.drawCircle(cx, cy + ledge + radius * 0.08f, radius, fill)
        fill.color = style.ledge
        c.drawCircle(cx, cy + ledge, radius, fill)
        val y = cy + sink
        fill.shader = LinearGradient(0f, y - radius, 0f, y + radius, style.top, style.bottom, Shader.TileMode.CLAMP)
        c.drawCircle(cx, y, radius, fill)
        fill.shader = LinearGradient(0f, y - radius * 0.85f, 0f, y, 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        r.set(cx - radius * 0.68f, y - radius * 0.86f, cx + radius * 0.68f, y - radius * 0.05f)
        c.drawOval(r, fill)
        fill.shader = null
        stroke.color = style.rim
        stroke.strokeWidth = radius * 0.1f
        c.drawCircle(cx, y, radius, stroke)
        if (icon != null) drawIcon(c, icon, cx, y, radius * 0.5f, Color.WHITE, style.text, k)
    }

    /** A navy panel with a light rim, like the HUD's pills. */
    fun panel(c: Canvas, box: RectF, k: Float) {
        val rad = 46f * k
        fill.shader = null
        fill.color = 0x66000000
        r.set(box.left, box.top + 12f * k, box.right, box.bottom + 12f * k)
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = LinearGradient(0f, box.top, 0f, box.bottom, 0xFF2F6BD0.toInt(), 0xFF13306F.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, rad, rad, fill)
        fill.shader = null
        stroke.color = 0xFF0A1A40.toInt()
        stroke.strokeWidth = 10f * k
        c.drawRoundRect(box, rad, rad, stroke)
        stroke.color = 0xFF8FD8FF.toInt()
        stroke.strokeWidth = 4f * k
        r.set(box.left + 9f * k, box.top + 9f * k, box.right - 9f * k, box.bottom - 9f * k)
        c.drawRoundRect(r, rad - 9f * k, rad - 9f * k, stroke)
    }

    /** A dark rounded pill (labels, counters). */
    fun pill(c: Canvas, box: RectF, k: Float, color: Int = 0xD9101C3E.toInt()) {
        fill.shader = null
        fill.color = color
        c.drawRoundRect(box, box.height() / 2f, box.height() / 2f, fill)
        stroke.color = 0x668FD8FF
        stroke.strokeWidth = 3f * k
        c.drawRoundRect(box, box.height() / 2f, box.height() / 2f, stroke)
    }

    /** One of the painted stars, centred, [size] pixels wide. */
    fun star(c: Canvas, cx: Float, cy: Float, size: Float, gold: Boolean, alpha: Int = 255) {
        val b = if (gold) starGold else starEmpty
        val h = size * b.height / b.width
        r.set(cx - size / 2f, cy - h / 2f, cx + size / 2f, cy + h / 2f)
        bmpPaint.alpha = alpha
        c.drawBitmap(b, null, r, bmpPaint)
        bmpPaint.alpha = 255
    }

    /** A short message in a dark bubble, fading with [alpha] (0..1). */
    fun toast(c: Canvas, msg: String, cx: Float, cy: Float, alpha: Float, k: Float) {
        if (alpha <= 0f) return
        val size = 44f * k
        val w = text.measure(msg, size) + 80f * k
        r.set(cx - w / 2f, cy - 50f * k, cx + w / 2f, cy + 50f * k)
        c.saveLayerAlpha(r.left - 20f * k, r.top - 20f * k, r.right + 20f * k, r.bottom + 30f * k, (255 * alpha).toInt())
        pill(c, r, k, 0xE6101C3E.toInt())
        text.white(c, msg, cx, cy + size * 0.36f, size, Color.WHITE, Paint.Align.CENTER, outline = 0.7f)
        c.restore()
    }

    /** White icons with a dark outline. */
    fun drawIcon(c: Canvas, icon: Icon, cx: Float, cy: Float, s: Float, color: Int, outline: Int, k: Float) {
        path.reset()
        when (icon) {
            Icon.BACK -> {
                path.moveTo(cx + s * 0.35f, cy - s * 0.75f)
                path.lineTo(cx - s * 0.45f, cy)
                path.lineTo(cx + s * 0.35f, cy + s * 0.75f)
                strokeIcon(c, color, outline, s * 0.38f)
            }
            Icon.PLAY -> {
                path.moveTo(cx - s * 0.45f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.7f, cy)
                path.lineTo(cx - s * 0.45f, cy + s * 0.7f)
                path.close()
                fillIcon(c, color, outline, s * 0.16f)
            }
            Icon.PAUSE -> {
                r.set(cx - s * 0.55f, cy - s * 0.65f, cx - s * 0.15f, cy + s * 0.65f)
                path.addRoundRect(r, s * 0.1f, s * 0.1f, Path.Direction.CW)
                r.set(cx + s * 0.15f, cy - s * 0.65f, cx + s * 0.55f, cy + s * 0.65f)
                path.addRoundRect(r, s * 0.1f, s * 0.1f, Path.Direction.CW)
                fillIcon(c, color, outline, s * 0.14f)
            }
            Icon.LOCK -> {
                // Shackle, then body.
                stroke.color = outline
                stroke.strokeWidth = s * 0.42f
                stroke.strokeCap = Paint.Cap.ROUND
                r.set(cx - s * 0.42f, cy - s * 0.95f, cx + s * 0.42f, cy - s * 0.05f)
                c.drawArc(r, 180f, 180f, false, stroke)
                stroke.color = color
                stroke.strokeWidth = s * 0.2f
                c.drawArc(r, 180f, 180f, false, stroke)
                stroke.strokeCap = Paint.Cap.BUTT
                r.set(cx - s * 0.7f, cy - s * 0.45f, cx + s * 0.7f, cy + s * 0.75f)
                path.addRoundRect(r, s * 0.18f, s * 0.18f, Path.Direction.CW)
                fillIcon(c, 0xFFFFD45A.toInt(), outline, s * 0.14f)
                fill.color = outline
                c.drawCircle(cx, cy + s * 0.1f, s * 0.14f, fill)
            }
            Icon.RESTART -> {
                stroke.color = outline
                stroke.strokeWidth = s * 0.42f
                stroke.strokeCap = Paint.Cap.ROUND
                r.set(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f)
                c.drawArc(r, -60f, 290f, false, stroke)
                stroke.color = color
                stroke.strokeWidth = s * 0.22f
                c.drawArc(r, -60f, 290f, false, stroke)
                stroke.strokeCap = Paint.Cap.BUTT
                val a = (-60f * PI / 180f).toFloat()
                val tx = cx + cos(a) * s * 0.6f
                val ty = cy + sin(a) * s * 0.6f
                path.moveTo(tx - s * 0.05f, ty - s * 0.42f)
                path.lineTo(tx + s * 0.4f, ty + s * 0.12f)
                path.lineTo(tx - s * 0.35f, ty + s * 0.22f)
                path.close()
                fillIcon(c, color, outline, s * 0.1f)
            }
            Icon.MAP -> {
                path.moveTo(cx - s * 0.8f, cy - s * 0.55f)
                path.lineTo(cx - s * 0.27f, cy - s * 0.75f)
                path.lineTo(cx + s * 0.27f, cy - s * 0.55f)
                path.lineTo(cx + s * 0.8f, cy - s * 0.75f)
                path.lineTo(cx + s * 0.8f, cy + s * 0.55f)
                path.lineTo(cx + s * 0.27f, cy + s * 0.75f)
                path.lineTo(cx - s * 0.27f, cy + s * 0.55f)
                path.lineTo(cx - s * 0.8f, cy + s * 0.75f)
                path.close()
                fillIcon(c, color, outline, s * 0.14f)
            }
        }
    }

    private fun strokeIcon(c: Canvas, color: Int, outline: Int, width: Float) {
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = outline
        stroke.strokeWidth = width * 1.6f
        c.drawPath(path, stroke)
        stroke.color = color
        stroke.strokeWidth = width
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun fillIcon(c: Canvas, color: Int, outline: Int, width: Float) {
        stroke.color = outline
        stroke.strokeWidth = width * 2f
        c.drawPath(path, stroke)
        fill.shader = null
        fill.color = color
        c.drawPath(path, fill)
        path.reset()
    }

    companion object {
        /** Screen unit: menus are designed on a 1080-wide portrait screen. */
        fun unit(w: Int, h: Int): Float = min(w / 1080f, h / 1800f)
    }
}
