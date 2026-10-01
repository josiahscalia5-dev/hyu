package com.islandblast.game.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.islandblast.game.model.Piece
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.SlicePhase
import com.islandblast.game.model.TargetKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a treasure-slicing level in stage units (Level 4: 941x1672, the approved
 * screen's pixel grid). Lagoon plate, the opening burst, treasure, cut halves and
 * debris, the blade, the sword hilt, then the HUD.
 */
class SliceRenderer(private val assets: SliceAssets, private val levelNumber: Int, private val levelName: String) {
    private val spec = assets.spec
    private val hud = spec.hud
    private val hudText = HudText(assets.fredoka)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val litPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0x000000, 0xFFFFFF)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val crack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val pose = FloatArray(3)
    private val path = Path()

    /** Score rolls up to its new value instead of jumping. */
    private var shownScore = 0f
    private var lastFrameTime = 0f

    fun reset() {
        shownScore = 0f
        lastFrameTime = 0f
    }

    fun draw(c: Canvas, game: SliceGame, fx: SliceEffects) {
        val now = game.time
        val dt = (now - lastFrameTime).coerceIn(0f, 0.1f)
        lastFrameTime = now
        // The scene painted past the stage edges fills whatever the phone shows around it.
        c.drawBitmap(assets.backgroundExt, -spec.extMarginX, -spec.extMarginY, bmpPaint)
        drawIntroGlow(c, now)
        drawPieces(c, game, now)
        drawIntroDebris(c, now)
        fx.drawWorld(c, now)
        drawLauncher(c, game, now)
        drawBlade(c, game, now)
        drawHud(c, game, now, dt)
        fx.drawOverlay(c, now)
        if (game.phase == SlicePhase.INTRO || (game.startedAt >= 0f && now - game.startedAt < BANNER_OUT)) {
            drawStartBanner(c, game, now)
        }
    }

    // ---- the opening burst (frame 0 is the approved screen) ------------------------

    private fun drawIntroGlow(c: Canvas, now: Float) {
        val k = ((now - 0.12f) / 0.78f).coerceIn(0f, 1f)
        if (k >= 1f) return
        fadePaint.alpha = (255 * (1f - smooth(k))).toInt()
        c.drawBitmap(assets.fxGlow, spec.introBox.l, spec.introBox.t, fadePaint)
        fadePaint.alpha = 255
    }

    private fun drawIntroDebris(c: Canvas, now: Float) {
        if (now >= 1f) return
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

    // ---- treasure ------------------------------------------------------------------

    private fun drawPieces(c: Canvas, game: SliceGame, now: Float) {
        for (p in game.pieces) {
            if (!p.alive) continue
            val s = p.spec
            val bmp = assets.targets[p.sprite]
            game.pose(p, pose)
            // Tossed treasure bursts out of the water.
            val born = if (p.hovering) 1f else ((now - p.bornAt) / 0.14f).coerceIn(0f, 1f)
            val grow = 0.55f + 0.45f * smooth(born)
            // A struck chest shudders.
            val sinceHit = now - p.lastHitAt
            val shake = if (sinceHit in 0f..0.25f) sin(sinceHit * 90f) * 9f * (1f - sinceHit / 0.25f) else 0f
            c.save()
            c.translate(pose[0] + shake, pose[1])
            c.rotate(pose[2] + shake * 0.6f)
            c.scale(p.scale * grow, p.scale * grow)
            c.translate(-s.cx, -s.cy)
            c.drawBitmap(bmp, s.box.l, s.box.t, bmpPaint)
            if (p.kind == TargetKind.CHEST && p.hp < game.rules.chestHits) drawCracks(c, p, game.rules.chestHits - p.hp)
            if (sinceHit in 0f..0.14f) {
                litPaint.alpha = (150 * (1f - sinceHit / 0.14f)).toInt()
                c.drawBitmap(bmp, s.box.l, s.box.t, litPaint)
            }
            c.restore()
        }
    }

    /** Splits in the wood, one more per cut taken, clipped to the chest's outline. */
    private fun drawCracks(c: Canvas, p: Piece, hits: Int) {
        val s = p.spec
        path.reset()
        val h = s.hull
        path.moveTo(h[0], h[1])
        for (i in 1 until h.size / 2) path.lineTo(h[2 * i], h[2 * i + 1])
        path.close()
        c.save()
        c.clipPath(path)
        val r = s.radius
        for (n in 0 until hits) {
            path.reset()
            // A jagged line across the chest, different for each crack.
            val seed = (p.id * 7 + n * 13)
            val a = (seed % 6) * 0.5f + n * 1.1f
            val dx = kotlin.math.cos(a)
            val dy = kotlin.math.sin(a)
            var x = s.cx - dx * r * 1.1f
            var y = s.cy - dy * r * 1.1f
            path.moveTo(x, y)
            for (j in 1..7) {
                val jag = (((seed + j * 31) % 9) - 4) * r * 0.035f
                x += dx * r * 0.32f - dy * jag
                y += dy * r * 0.32f + dx * jag
                path.lineTo(x, y)
            }
            crack.color = 0xFF2A1204.toInt()
            crack.strokeWidth = r * 0.06f
            c.drawPath(path, crack)
            crack.color = 0x99FFD27A.toInt()
            crack.strokeWidth = r * 0.02f
            c.drawPath(path, crack)
        }
        c.restore()
    }

    // ---- the blade and the sword hilt ------------------------------------------------

    /** The blade's trail: a crescent of white light edged in blue, like the painted slash. */
    private fun drawBlade(c: Canvas, game: SliceGame, now: Float) {
        val pts = game.blade
        if (pts.size < 2) return
        val n = pts.size
        for ((width, color, alpha) in BLADE_LAYERS) {
            beam.color = color
            for (i in 1 until n) {
                val a = pts[i - 1]
                val b = pts[i]
                val age = (now - b.t) / SliceGame.BLADE_LIFE
                val u = i / (n - 1f)
                val fade = (1f - age).coerceIn(0f, 1f)
                if (fade <= 0f) continue
                beam.alpha = (alpha * fade).toInt()
                beam.strokeWidth = width * (0.15f + 0.85f * u) * (0.4f + 0.6f * fade)
                c.drawLine(a.x, a.y, b.x, b.y, beam)
            }
        }
        val head = pts.last()
        if (game.swiping && now - head.t < 0.1f) {
            add.shader = RadialGradient(head.x, head.y, 42f, intArrayOf(0xFFFFFFFF.toInt(), 0x9956D8FF.toInt(), 0),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(head.x, head.y, 42f, add)
            add.shader = null
        }
    }

    /** The sword hilt rests where it is painted and turns a little toward the blade. */
    private fun drawLauncher(c: Canvas, game: SliceGame, now: Float) {
        val box = spec.launcherBox
        var turn = 0f
        val head = game.blade.lastOrNull()
        if (head != null && now - head.t < 0.3f) {
            val rest = atan2(spec.tipY - spec.pivotY, spec.tipX - spec.pivotX)
            val want = atan2(head.y - spec.pivotY, head.x - spec.pivotX)
            var d = Math.toDegrees((want - rest).toDouble()).toFloat()
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            turn = d.coerceIn(-28f, 28f) * (1f - (now - head.t) / 0.3f)
        }
        c.save()
        c.rotate(turn, spec.pivotX, spec.pivotY)
        c.drawBitmap(assets.launcher, box.l, box.t, bmpPaint)
        val k = 0.6f + 0.4f * sin(now * 5f)
        val glow = if (game.swiping) 1f else 0.55f
        add.shader = RadialGradient(spec.tipX, spec.tipY, 46f * k + 8f,
            intArrayOf(((0xCC * glow).toInt() shl 24) or 0xFFFFFF, 0x6656D8FF, 0), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP)
        c.drawCircle(spec.tipX, spec.tipY, 46f * k + 8f, add)
        add.shader = null
        c.restore()
    }

    // ---- HUD -----------------------------------------------------------------------

    private fun drawHud(c: Canvas, game: SliceGame, now: Float, dt: Float) {
        for (i in 0 until min(3, game.stars)) drawStar(c, i)

        // Timer: white on the dark pill beside the stopwatch.
        val secs = game.timerSeconds
        val bonus = (now - game.lastTimeBonusAt).let { if (it in 0f..0.6f) 1f - it / 0.6f else 0f }
        val low = secs <= 10 && game.phase == SlicePhase.PLAYING
        val timerColor = when {
            bonus > 0f -> lerpColor(Color.WHITE, 0xFF8CFF6A.toInt(), bonus)
            low -> lerpColor(Color.WHITE, 0xFFFF5A48.toInt(), 0.5f + 0.5f * sin(now * 10f))
            else -> Color.WHITE
        }
        val t = hud.timer
        hudText.white(c, "%d:%02d".format(secs / 60, secs % 60), t.x, t.y, t.size * (1f + 0.1f * bonus), timerColor,
            outline = TIMER_OUTLINE)

        // Score counts up.
        shownScore = approach(shownScore, game.score.toFloat(), dt, 3000f)
        hudText.cream(c, "%,d".format(shownScore.roundToInt()), hud.score, outline = SCORE_OUTLINE)

        // Combo: pops on every cut while the chain is going.
        val m = game.multiplier
        val live = now - game.lastCutAt <= game.rules.comboWindow || game.over
        if (m >= 2 && live) {
            val kc = ((now - game.lastCutAt) / 0.3f).coerceIn(0f, 1f)
            val pop = 1f + 0.22f * (1f - smooth(kc))
            val cb = hud.comboBox
            val fade = if (game.over) 1f else (1f - ((now - game.lastCutAt) - (game.rules.comboWindow - 0.3f)) / 0.3f).coerceIn(0f, 1f)
            c.saveLayerAlpha(cb.l - 40f, cb.t - 40f, cb.r + 40f, cb.b + 40f, (255 * fade).toInt())
            c.scale(pop, pop, cb.cx, cb.cy)
            val w = hud.combo
            hudText.gold(c, "Combo", w.x, w.y, w.size, Paint.Align.LEFT, w.angle, w.scaleX, rim = false)
            drawComboNumber(c, "x$m")
            c.restore()
        }
        drawTimeBar(c, game, now, low)
    }

    private fun drawComboNumber(c: Canvas, s: String) {
        val n = hud.comboNumber
        val cb = hud.comboBox
        val w = hudText.measure(s, n.size) * n.scaleX
        val size = if (w <= cb.w) n.size else n.size * cb.w / w
        val sw = w * size / n.size
        val left = min(n.x + (n.width - sw) / 2f, cb.r - sw - 6f).coerceAtLeast(cb.l)
        c.save()
        c.rotate(n.angle, n.x, n.y)
        hudText.gold(c, s, left, n.y - (n.size - size) * 0.3f, size, Paint.Align.LEFT, 0f, n.scaleX, rim = false)
        c.restore()
    }

    /** The painted yellow fill, drawn 3-slice so its rounded ends survive any length. */
    private fun drawTimeBar(c: Canvas, game: SliceGame, now: Float, low: Boolean) {
        val track = hud.timeBarTrack
        val fill = hud.timeBarFill
        val bmp = assets.barFill
        val full = track.r - fill.l
        val w = full * game.timeFraction
        if (w < 2f) return
        val cap = min(CAP, bmp.width / 2f)
        val paint = if (low) fadePaint.apply { alpha = (170 + 85 * sin(now * 10f)).toInt() } else bmpPaint
        val capW = min(cap, w / 2f)
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

    private fun drawStar(c: Canvas, i: Int) {
        val box = hud.stars[i]
        val bmp = assets.starGold
        val scale = 1.06f
        dst.set(box.cx - bmp.width / 2f * scale, box.cy - bmp.height / 2f * scale, box.cx + bmp.width / 2f * scale,
            box.cy + bmp.height / 2f * scale)
        c.drawBitmap(bmp, null, dst, bmpPaint)
    }

    // ---- the start ------------------------------------------------------------------

    /** "Level 4 · Mystic Harvest · Goal · Swipe to slice!", with a ghost blade showing how. */
    private fun drawStartBanner(c: Canvas, game: SliceGame, now: Float) {
        val out = if (game.startedAt >= 0f) ((now - game.startedAt) / BANNER_OUT).coerceIn(0f, 1f) else 0f
        val inK = (now / 0.35f).coerceIn(0f, 1f)
        val a = smooth(inK) * (1f - out)
        if (a <= 0f) return
        val cx = spec.stageW / 2f
        val top = 470f
        c.saveLayerAlpha(0f, top - 40f, spec.stageW, top + 520f, (255 * a).toInt())
        fill.shader = null
        fill.color = 0xB0081A3A.toInt()
        dst.set(70f, top, spec.stageW - 70f, top + 420f)
        c.drawRoundRect(dst, 46f, 46f, fill)
        fill.color = 0x668FD8FF
        beam.color = 0x998FD8FF.toInt()
        beam.alpha = 160
        beam.strokeWidth = 4f
        c.drawRoundRect(dst, 46f, 46f, beam)
        hudText.white(c, "Level $levelNumber", cx, top + 82f, 54f, Color.WHITE, Paint.Align.CENTER, outline = 0.8f)
        hudText.gold(c, levelName, cx, top + 170f, 84f, Paint.Align.CENTER, 0f)
        hudText.white(c, "Goal: %,d points in %d seconds".format(game.rules.goal, game.rules.seconds.toInt()), cx, top + 250f,
            42f, 0xFFFFF3B8.toInt(), Paint.Align.CENTER, outline = 0.7f)
        val pulse = 1f + 0.06f * sin(now * 6f)
        hudText.gold(c, "Swipe to slice!", cx, top + 350f, 66f * pulse, Paint.Align.CENTER, 0f)
        c.restore()
        // A ghost blade sweeping across, showing the gesture.
        if (game.phase == SlicePhase.INTRO) {
            val k = (now % 1.6f) / 1.0f
            if (k <= 1f) {
                val x0 = 180f
                val y0 = 1080f
                val x1 = 760f
                val y1 = 940f
                val hx = x0 + (x1 - x0) * k
                val hy = y0 + (y1 - y0) * k - sin(k * Math.PI.toFloat()) * 60f
                val tail = max(0f, k - 0.25f)
                val tx = x0 + (x1 - x0) * tail
                val ty = y0 + (y1 - y0) * tail - sin(tail * Math.PI.toFloat()) * 60f
                val fade = (1f - abs(k - 0.5f) * 2f).coerceIn(0f, 1f)
                for ((width, color, alpha) in BLADE_LAYERS) {
                    beam.color = color
                    beam.alpha = (alpha * 0.8f * fade * a).toInt()
                    beam.strokeWidth = width * 0.8f
                    c.drawLine(tx, ty, hx, hy, beam)
                }
                // A fingertip at the head.
                fill.color = Color.argb((200 * fade * a).toInt(), 255, 255, 255)
                c.drawCircle(hx, hy, 22f, fill)
            }
        }
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
        private const val CAP = 26f
        private const val BANNER_OUT = 0.2f
        private const val TIMER_OUTLINE = 0.55f
        private const val SCORE_OUTLINE = 0.7f

        /** Cyan halo, blue edge, white core: the painted crescent's bands (width, colour, alpha). */
        private val BLADE_LAYERS = listOf(
            Triple(64f, 0xFF7FE0FF.toInt(), 110),
            Triple(34f, 0xFF2A62F0.toInt(), 255),
            Triple(20f, 0xFFFFFFFF.toInt(), 255),
        )
    }
}
