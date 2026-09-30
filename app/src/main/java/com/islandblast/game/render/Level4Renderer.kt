package com.islandblast.game.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.islandblast.game.model.AimPreview
import com.islandblast.game.model.Level4Game
import com.islandblast.game.model.Phase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws Level 4 in stage units (941x1672, the approved screen's pixel grid). Layer
 * order follows the screen: lagoon plate, the opening burst, hovering treasure,
 * harvest effects, aim guide, bolt, launcher, then HUD.
 */
class Level4Renderer(private val assets: Level4Assets) {
    private val spec = assets.spec
    private val hud = spec.hud
    private val hudText = HudText(assets.fredoka)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val litPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0x000000, 0xFFFFFF)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val dimStar = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0xFF3A1D0E.toInt(), PorterDuff.Mode.SRC_ATOP)
    }
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val src = Rect()
    private val dst = RectF()
    private val hov = FloatArray(3)
    private val p = FloatArray(2)
    private val q = FloatArray(2)

    /** Score rolls up to its new value instead of jumping. */
    private var shownScore = -1f
    private var lastFrameTime = 0f

    fun reset() {
        shownScore = -1f
    }

    fun draw(c: Canvas, game: Level4Game, fx: Level4Effects) {
        val now = game.time
        val dt = (now - lastFrameTime).coerceIn(0f, 0.1f)
        lastFrameTime = now
        // The scene painted past the stage edges fills whatever the phone shows around it.
        c.drawBitmap(assets.backgroundExt, -spec.extMarginX, -spec.extMarginY, bmpPaint)
        drawIntroGlow(c, now)
        val preview = if (game.canShoot && game.aimTouched && !game.introActive) game.aimPreview() else null
        drawTreasure(c, game, now, preview)
        drawIntroDebris(c, now)
        fx.draw(c, now)
        if (preview != null) drawAimGuide(c, game, preview, now)
        drawBolt(c, game, now)
        drawLauncher(c, game, now)
        drawHud(c, game, now, dt)
        when {
            game.phase == Phase.WON -> drawEndCard(c, game, "Level Complete!", "Tap for Level 5")
            game.phase == Phase.LOST -> drawEndCard(c, game, "Time's Up!", "Tap to try again")
            game.paused -> drawPaused(c)
        }
    }

    // ---- the opening burst ---------------------------------------------------------

    /** The painted glow and trail stay where they are and fade as the burst settles. */
    private fun drawIntroGlow(c: Canvas, now: Float) {
        val k = ((now - 0.12f) / 0.78f).coerceIn(0f, 1f)
        if (k >= 1f) return
        fadePaint.alpha = (255 * (1f - smooth(k))).toInt()
        c.drawBitmap(assets.fxGlow, spec.introBox.l, spec.introBox.t, fadePaint)
        fadePaint.alpha = 255
    }

    /** The painted loose gems and sparks fly apart over the first second. */
    private fun drawIntroDebris(c: Canvas, now: Float) {
        if (now >= INTRO_FX) return
        val ox = spec.introBox.l
        val oy = spec.introBox.t
        fadePaint.alpha = (255 * (1f - ((now - 0.25f) / 0.65f).coerceIn(0f, 1f))).toInt()
        for (piece in spec.introPieces) {
            val r = piece.rect
            src.set((r.l - ox).toInt(), (r.t - oy).toInt(), (r.r - ox).toInt(), (r.b - oy).toInt())
            dst.set(r.l, r.t, r.r, r.b)
            val x = (piece.cx - spec.burstX) * 1.6f * now
            val y = (piece.cy - spec.burstY) * 1.6f * now + 0.5f * 900f * now * now
            val spin = (((piece.cx * 31 + piece.cy * 17).toInt() % 400) - 200).toFloat()
            c.save()
            c.translate(x, y)
            c.rotate(spin * now, piece.cx, piece.cy)
            c.drawBitmap(assets.fxDebris, src, dst, fadePaint)
            c.restore()
        }
        fadePaint.alpha = 255
    }

    // ---- treasure --------------------------------------------------------------------

    private fun drawTreasure(c: Canvas, game: Level4Game, now: Float, preview: AimPreview?) {
        val pulse = 0.5f + 0.5f * sin(now * 9f)
        for (t in game.treasures) {
            if (!t.alive) continue
            val s = t.spec
            val bmp = assets.targets[t.index]
            game.hover(t, hov)
            c.save()
            c.translate(hov[0], hov[1])
            c.rotate(hov[2], s.cx, s.cy)
            c.drawBitmap(bmp, s.box.l, s.box.t, bmpPaint)
            if (preview != null && t.index in preview.hits) {
                // Locked on: the treasure glints and a thin ring marks it.
                litPaint.alpha = (18 + 30 * pulse).toInt()
                c.drawBitmap(bmp, s.box.l, s.box.t, litPaint)
                ring.color = 0xFFBFF2FF.toInt()
                ring.alpha = (120 + 90 * pulse).toInt()
                ring.strokeWidth = 3f
                c.drawCircle(s.cx, s.cy, s.radius * 0.95f + 8f + 3f * pulse, ring)
            }
            c.restore()
        }
    }

    // ---- aim guide, bolt, launcher ---------------------------------------------------

    /** Glowing beads along the curve the bolt will take, flowing toward the aim point. */
    private fun drawAimGuide(c: Canvas, game: Level4Game, preview: AimPreview, now: Float) {
        val path = preview.path
        val gap = 30f
        var d = (now * 140f) % gap
        while (d < preview.end) {
            path.at(d, p)
            val u = d / max(1f, preview.end)
            val r = 7f - 3f * u
            add.color = 0x6632C8FF
            c.drawCircle(p[0], p[1], r * 2.2f, add)
            add.color = 0xE6FFFFFF.toInt()
            c.drawCircle(p[0], p[1], r * 0.8f, add)
            d += gap
        }
        if (preview.end >= path.length - 1f) {
            path.at(path.length, p)
            val k = (now * 2f) % 1f
            ring.color = 0xFFBFF2FF.toInt()
            ring.alpha = (255 * (1f - k)).toInt()
            ring.strokeWidth = 4f
            c.drawCircle(p[0], p[1], 14f + 20f * k, ring)
            ring.alpha = 230
            c.drawCircle(p[0], p[1], 10f, ring)
        }
    }

    /** The mystic bolt: a crescent of white light edged in blue, like the painted one. */
    private fun drawBolt(c: Canvas, game: Level4Game, now: Float) {
        val b = game.bolt ?: return
        var head = min(b.dist, b.end)
        var tail = max(0f, head - TRAIL)
        var fade = 1f
        if (b.done) {
            val k = ((now - b.doneAt) / 0.28f).coerceIn(0f, 1f)
            if (k >= 1f) return
            tail += (head - tail) * smooth(k)
            fade = 1f - k
        }
        if (head - tail < 1f) return
        val step = 8f
        // Cyan halo, blue edge, white core: the painted crescent's bands.
        val layers = arrayOf(
            floatArrayOf(3.2f, 0f), floatArrayOf(1.7f, 1f), floatArrayOf(1.05f, 2f),
        )
        for (layer in layers) {
            when (layer[1].toInt()) {
                0 -> { beam.color = 0xFF7FE0FF.toInt(); beam.alpha = (110 * fade).toInt() }
                1 -> { beam.color = 0xFF2A62F0.toInt(); beam.alpha = (255 * fade).toInt() }
                else -> { beam.color = 0xFFFFFFFF.toInt(); beam.alpha = (255 * fade).toInt() }
            }
            var d = tail
            b.path.at(d, p)
            while (d < head) {
                val d2 = min(head, d + step)
                b.path.at(d2, q)
                val u = (d2 - tail) / (head - tail)
                beam.strokeWidth = BOLT_WIDTH * layer[0] * (0.2f + 0.8f * u)
                c.drawLine(p[0], p[1], q[0], q[1], beam)
                p[0] = q[0]; p[1] = q[1]
                d = d2
            }
        }
        if (!b.done) {
            b.path.at(head, p)
            add.shader = RadialGradient(p[0], p[1], 46f, intArrayOf(0xFFFFFFFF.toInt(), 0x9956D8FF.toInt(), 0),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(p[0], p[1], 46f, add)
            add.shader = null
        }
    }

    private fun drawLauncher(c: Canvas, game: Level4Game, now: Float) {
        val box = spec.launcherBox
        c.save()
        c.rotate(game.launcherTurn, spec.pivotX, spec.pivotY)
        c.drawBitmap(assets.launcher, box.l, box.t, bmpPaint)
        c.restore()
        if (game.canShoot && !game.introActive) {
            game.tipFor(game.launcherTurn, p)
            val k = 0.6f + 0.4f * sin(now * 5f)
            add.shader = RadialGradient(p[0], p[1], 40f * k + 8f, intArrayOf(0xCCFFFFFF.toInt(), 0x6656D8FF, 0),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(p[0], p[1], 40f * k + 8f, add)
            add.shader = null
        }
    }

    // ---- HUD -----------------------------------------------------------------------

    private fun drawHud(c: Canvas, game: Level4Game, now: Float, dt: Float) {
        // Third star, once earned (the plate paints two gold and one empty).
        if (game.stars >= 3) drawStar(c, 2, bmpPaint)

        // Timer: white on the dark pill beside the stopwatch.
        val secs = game.timerSeconds
        val bonus = (now - game.lastTimeBonusAt).let { if (it in 0f..0.6f) 1f - it / 0.6f else 0f }
        val low = secs <= 10 && !game.over
        val timerColor = when {
            bonus > 0f -> lerpColor(Color.WHITE, 0xFF8CFF6A.toInt(), bonus)
            low -> lerpColor(Color.WHITE, 0xFFFF5A48.toInt(), 0.5f + 0.5f * sin(now * 10f))
            else -> Color.WHITE
        }
        val t = hud.timer
        if (secs == game.rules.startSeconds.toInt() && timerColor == Color.WHITE) {
            drawPainted(c, "timer")
        } else {
            hudText.white(c, "%d:%02d".format(secs / 60, secs % 60), t.x, t.y, t.size * (1f + 0.1f * bonus), timerColor,
                outline = TIMER_OUTLINE)
        }

        // Score counts up.
        if (shownScore < 0f) shownScore = game.score.toFloat()
        shownScore = approach(shownScore, game.score.toFloat(), dt, 4000f)
        val shown = shownScore.roundToInt()
        if (shown == game.rules.startScore) drawPainted(c, "score")
        else hudText.cream(c, "%,d".format(shown), hud.score, outline = SCORE_OUTLINE)

        // Combo: pops on every harvest.
        if (game.combo >= 2) {
            val kc = ((now - game.lastHitAt) / 0.3f).coerceIn(0f, 1f)
            val pop = 1f + 0.22f * (1f - smooth(kc))
            val cb = hud.comboBox
            c.save()
            c.scale(pop, pop, cb.cx, cb.cy)
            if (game.combo == game.rules.startCombo) {
                drawPainted(c, "combo")
            } else {
                val w = hud.combo
                hudText.gold(c, "Combo", w.x, w.y, w.size, Paint.Align.LEFT, w.angle, w.scaleX, rim = false)
                drawComboNumber(c, "x${game.combo}")
            }
            c.restore()
        }

        drawTimeBar(c, game, now, low)
    }

    private fun drawPainted(c: Canvas, key: String) {
        val box = hud.painted.getValue(key)
        c.drawBitmap(assets.painted.getValue(key), box.l, box.t, bmpPaint)
    }

    /**
     * "x8" exactly where it is painted; other numbers are centred on the same line (in
     * its tilted frame) and shrink only if they would leave the combo box.
     */
    private fun drawComboNumber(c: Canvas, s: String) {
        val n = hud.comboNumber
        val cb = hud.comboBox
        val w = hudText.measure(s, n.size) * n.scaleX
        val room = cb.w
        val size = if (w <= room) n.size else n.size * room / w
        val sw = w * size / n.size
        // Centred where "x8" is painted, then pulled left so it never leaves the box.
        val left = min(n.x + (n.width - sw) / 2f, cb.r - sw - 6f).coerceAtLeast(cb.l)
        c.save()
        c.rotate(n.angle, n.x, n.y)
        hudText.gold(c, s, left, n.y - (n.size - size) * 0.3f, size, Paint.Align.LEFT, 0f, n.scaleX, rim = false)
        c.restore()
    }

    /** The painted yellow fill, drawn 3-slice so its rounded ends survive any length. */
    private fun drawTimeBar(c: Canvas, game: Level4Game, now: Float, low: Boolean) {
        val track = hud.timeBarTrack
        val fill = hud.timeBarFill
        val bmp = assets.barFill
        val full = track.r - fill.l
        val w = full * game.timeFraction
        if (w < 2f) return
        val cap = min(CAP, bmp.width / 2f)
        val paint = if (low) fadePaint.apply { alpha = (170 + 85 * sin(now * 10f)).toInt() } else bmpPaint
        val capW = min(cap, w / 2f)
        // Left cap, stretched middle, right cap.
        src.set(0, 0, cap.toInt(), bmp.height)
        dst.set(fill.l, fill.t, fill.l + capW, fill.b)
        c.drawBitmap(bmp, src, dst, paint)
        if (w > 2f * capW) {
            src.set(cap.toInt(), 0, (bmp.width - cap).toInt(), bmp.height)
            dst.set(fill.l + capW, fill.t, fill.l + w - capW, fill.b)
            c.drawBitmap(bmp, src, dst, paint)
        }
        src.set((bmp.width - cap).toInt(), 0, bmp.width, bmp.height)
        dst.set(fill.l + w - capW, fill.t, fill.l + w, fill.b)
        c.drawBitmap(bmp, src, dst, paint)
        fadePaint.alpha = 255
    }

    private fun drawStar(c: Canvas, i: Int, paint: Paint, cx: Float? = null, cy: Float? = null, scale: Float = 1.06f) {
        val box = hud.stars[i]
        val bmp = assets.starGold
        val x = cx ?: box.cx
        val y = cy ?: box.cy
        dst.set(x - bmp.width / 2f * scale, y - bmp.height / 2f * scale, x + bmp.width / 2f * scale,
            y + bmp.height / 2f * scale)
        c.drawBitmap(bmp, null, dst, paint)
    }

    // ---- overlays --------------------------------------------------------------------

    private fun drawPaused(c: Canvas) {
        c.drawColor(0x8C000000.toInt())
        val cx = spec.stageW / 2f
        val cy = spec.stageH * 0.42f
        hudText.gold(c, "Paused", cx, cy, 96f, Paint.Align.CENTER, 0f)
        hudText.white(c, "Tap to resume", cx, cy + 76f, 40f, Color.WHITE, Paint.Align.CENTER)
    }

    private fun drawEndCard(c: Canvas, game: Level4Game, title: String, hint: String) {
        c.drawColor(0x99000000.toInt())
        val cx = spec.stageW / 2f
        val cy = spec.stageH * 0.4f
        hudText.gold(c, title, cx, cy, 82f, Paint.Align.CENTER, 0f)
        for (i in 0 until 3) {
            drawStar(c, i, if (i < game.stars) bmpPaint else dimStar, cx + (i - 1) * 100f, cy + 80f, 1.1f)
        }
        hudText.white(c, "Score %,d".format(game.score), cx, cy + 200f, 52f, Color.WHITE, Paint.Align.CENTER)
        hudText.white(c, hint, cx, cy + 270f, 38f, Color.WHITE, Paint.Align.CENTER)
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun smooth(k: Float) = k * k * (3f - 2f * k)

    private fun approach(v: Float, target: Float, dt: Float, rate: Float): Float {
        if (abs(target - v) < 0.5f) return target
        val step = max(rate * dt, abs(target - v) * min(1f, dt * 8f))
        return if (target > v) min(target, v + step) else max(target, v - step)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    companion object {
        const val INTRO_FX = 1.0f
        private const val TRAIL = 430f
        private const val BOLT_WIDTH = 26f
        private const val CAP = 26f
        // The painted timer and score have lighter outlines than Level 5's lettering.
        private const val TIMER_OUTLINE = 0.55f
        private const val SCORE_OUTLINE = 0.7f
    }
}
