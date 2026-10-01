package com.islandblast.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.islandblast.game.model.BlockState
import com.islandblast.game.model.GameColor
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.Phase
import com.islandblast.game.model.TextSpot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws Level 5 in stage units (1024x1536, the approved reference's pixel grid).
 * Layer order follows the reference: temple plate, blocks, hit effects, aim guide,
 * ball, then HUD.
 */
class Renderer(private val assets: Assets) {
    /** Draw the pause and end cards here; off when a level host draws its own menus. */
    var overlays = true

    private val spec = assets.spec
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val flashPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val breakPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val hudText = HudText(assets.fredoka)
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()
    private val trail = ArrayDeque<FloatArray>()
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    /** Score/coins roll up to their new values instead of jumping. */
    private var shownScore = -1f
    private var shownCoins = -1f
    private var lastFrameTime = 0f

    fun reset() {
        shownScore = -1f
        shownCoins = -1f
        trail.clear()
    }

    fun draw(c: Canvas, game: Level5Game, fx: Effects) {
        val now = game.time
        val dt = (now - lastFrameTime).coerceIn(0f, 0.1f)
        lastFrameTime = now
        // The scene painted past the stage edges fills whatever the phone shows around it.
        c.drawBitmap(assets.backgroundExt, -spec.extMarginX, -spec.extMarginY, bmpPaint)
        drawBlocks(c, game, now)
        drawIntro(c, now)
        drawShiftMarks(c, game, now)
        fx.draw(c, now)
        if (game.canShoot || game.phase == Phase.READY) drawAimGuide(c, game, now)
        drawBall(c, game, now)
        drawHud(c, game, now, dt)
        drawGoalPanel(c, game, now)
        if (!overlays) return
        when {
            game.phase == Phase.WON -> drawEndCard(c, game, "Level Complete!", "Tap to play again")
            game.phase == Phase.LOST -> drawEndCard(c, game, "Time's Up!", "Tap to try again")
            game.paused -> drawPaused(c)
        }
    }

    // ---- blocks ------------------------------------------------------------------

    private fun drawBlocks(c: Canvas, game: Level5Game, now: Float) {
        val intro = game.rules.introSeconds
        for (b in game.board.blocks) {
            if (!b.alive) {
                val t = now - b.clearedAt
                if (b.clearedAt >= 0f && t in 0f..BREAK_TIME) drawBreak(c, b, t)
                continue
            }
            val scalePop: Float
            val shake: Float
            val shifting = b.shiftAt >= 0f && now - b.shiftAt < SHIFT_TIME
            val kShift = if (shifting) ((now - b.shiftAt) / SHIFT_TIME).coerceIn(0f, 1f) else 1f
            scalePop = if (shifting) 1f + 0.12f * sin(PI.toFloat() * kShift) else 1f
            val tb = now - b.bumpAt
            shake = if (b.bumpAt >= 0f && tb < 0.25f) sin(tb * 70f) * 5f * (1f - tb / 0.25f) else 0f

            val art = assets.blocks.getValue(b.color)
            if (b.spec.normalize && now < intro) {
                // Opening frame shows the painted hue; ease it to the game colour as the intro ends.
                val k = ((now - (intro - 0.5f)) / 0.5f).coerceIn(0f, 1f)
                drawBlockArt(c, b, assets.blocksRef, bmpPaint, 1f, 0f)
                if (k > 0f) {
                    fadePaint.alpha = (255 * k).toInt()
                    drawBlockArt(c, b, art, fadePaint, 1f, 0f)
                }
                continue
            }
            if (shifting) {
                val from = assets.blocks.getValue(b.shiftFrom ?: b.color)
                drawBlockArt(c, b, from, bmpPaint, scalePop, shake)
                fadePaint.alpha = (255 * smooth(kShift)).toInt()
                drawBlockArt(c, b, art, fadePaint, scalePop, shake)
                val flash = sin(PI.toFloat() * kShift)
                flashPaint.colorFilter = LightingColorFilter(0xFFFFFF, gray(0.55f))
                flashPaint.alpha = (190 * flash).toInt()
                drawBlockArt(c, b, art, flashPaint, scalePop, shake)
            } else {
                drawBlockArt(c, b, art, bmpPaint, scalePop, shake)
            }
            if (b.bumpAt >= 0f && tb < 0.18f) {
                flashPaint.colorFilter = LightingColorFilter(0xFFFFFF, gray(0.5f))
                flashPaint.alpha = (150 * (1f - tb / 0.18f)).toInt()
                drawBlockArt(c, b, art, flashPaint, 1f, shake)
            }
        }
    }

    /**
     * A cleared block bursts apart: its own art splits into four chunks that fly out
     * from the centre, spin and fade, flashing white for the first instant.
     */
    private fun drawBreak(c: Canvas, b: BlockState, t: Float) {
        val k = t / BREAK_TIME
        val atlas = assets.blocks.getValue(b.color)
        val r = b.spec.rect
        val flash = (1f - t / 0.07f).coerceIn(0f, 1f)
        breakPaint.alpha = (255 * (1f - k * k)).toInt()
        breakPaint.colorFilter = if (flash > 0f) LightingColorFilter(0xFFFFFF, gray(0.7f * flash)) else null
        for (part in b.spec.parts) {
            if (part.w < 6f || part.h < 6f) continue
            val mx = part.cx
            val my = part.cy
            for (qx in 0..1) for (qy in 0..1) {
                val l = if (qx == 0) part.l else mx
                val rr = if (qx == 0) mx else part.r
                val tp = if (qy == 0) part.t else my
                val bt = if (qy == 0) my else part.b
                val pcx = (l + rr) / 2f
                val pcy = (tp + bt) / 2f
                var dx = pcx - r.cx
                var dy = pcy - r.cy
                val n = hypot(dx, dy).coerceAtLeast(1f)
                dx /= n; dy /= n
                val speed = 330f + ((b.index * 7 + qx * 3 + qy * 5) % 5) * 40f
                val ox = dx * speed * t
                val oy = dy * speed * t + 0.5f * 1400f * t * t
                val spin = (if ((qx + qy + b.index) % 2 == 0) 1f else -1f) * 260f * t
                val sc = 1f - 0.35f * k
                src.set((l - spec.atlasX).toInt(), (tp - spec.atlasY).toInt(), (rr - spec.atlasX).toInt(), (bt - spec.atlasY).toInt())
                c.save()
                c.translate(pcx + ox, pcy + oy)
                c.rotate(spin)
                c.scale(sc, sc)
                dst.set(l - pcx, tp - pcy, rr - pcx, bt - pcy)
                c.drawBitmap(atlas, src, dst, breakPaint)
                c.restore()
            }
        }
    }

    private fun drawBlockArt(c: Canvas, b: BlockState, atlas: Bitmap, p: Paint, scale: Float, dx: Float) {
        val r = b.spec.rect
        c.save()
        if (scale != 1f || dx != 0f) {
            c.translate(dx, 0f)
            c.scale(scale, scale, r.cx, r.cy)
        }
        for (part in b.spec.parts) {
            src.set(
                (part.l - spec.atlasX).toInt(), (part.t - spec.atlasY).toInt(),
                (part.r - spec.atlasX).toInt(), (part.b - spec.atlasY).toInt(),
            )
            dst.set(part.l, part.t, part.r, part.b)
            c.drawBitmap(atlas, src, dst, p)
        }
        c.restore()
    }

    // ---- opening frame -------------------------------------------------------------

    /** The reference's frozen explosion, released into motion over the first second. */
    private fun drawIntro(c: Canvas, now: Float) {
        if (now >= INTRO_FX) return
        val ox = spec.atlasX
        val oy = spec.atlasY
        val bgK = ((now - 0.15f) / 0.65f).coerceIn(0f, 1f)
        if (bgK < 1f) {
            fadePaint.alpha = (255 * (1f - bgK)).toInt()
            c.drawBitmap(assets.fxIntroBg, ox, oy, fadePaint)
        }
        for (p in spec.introPieces) {
            src.set((p.rect.l - ox).toInt(), (p.rect.t - oy).toInt(), (p.rect.r - ox).toInt(), (p.rect.b - oy).toInt())
            dst.set(p.rect.l, p.rect.t, p.rect.r, p.rect.b)
            c.save()
            if (p.kind == "burst") {
                val k = (now / 0.45f).coerceIn(0f, 1f)
                if (k >= 1f) { c.restore(); continue }
                val s = 1f + 0.4f * k
                c.scale(s, s, spec.flashX, spec.flashY)
                fadePaint.alpha = (255 * (1f - k)).toInt()
                c.drawBitmap(assets.fxIntroBurst, src, dst, fadePaint)
            } else {
                val vx = (p.cx - spec.flashX) * 1.7f
                val vy = (p.cy - spec.flashY) * 1.7f
                val x = vx * now
                val y = vy * now + 0.5f * 900f * now * now
                val spin = (((p.cx * 31 + p.cy * 17).toInt() % 400) - 200).toFloat()
                c.translate(x, y)
                c.rotate(spin * now, p.cx, p.cy)
                fadePaint.alpha = (255 * (1f - ((now - 0.25f) / 0.65f).coerceIn(0f, 1f))).toInt()
                c.drawBitmap(assets.fxIntro, src, dst, fadePaint)
            }
            c.restore()
        }
        fadePaint.alpha = 255
    }

    // ---- colour-shift markers ---------------------------------------------------------

    private fun drawShiftMarks(c: Canvas, game: Level5Game, now: Float) {
        for (b in game.board.blocks) {
            if (!b.alive || !b.shifted) continue
            val k = ((now - b.shiftAt) / SHIFT_TIME).coerceIn(0f, 1f)
            val r = b.spec.rect
            val rad = min(r.w, r.h) * 0.27f * (0.6f + 0.4f * smooth(k))
            drawSwirl(c, r.cx, r.cy, rad, b.color, 360f * smooth(k), (255 * smooth(min(1f, k * 2f))).toInt())
        }
    }

    /** Circular-arrows badge (as in the colour-shift reference) on blocks that just changed. */
    private fun drawSwirl(c: Canvas, x: Float, y: Float, r: Float, color: GameColor, rot: Float, alpha: Int) {
        fill.shader = null
        fill.color = darken(color.swatch, 0.72f)
        fill.alpha = alpha
        c.drawCircle(x, y, r, fill)
        stroke.color = 0xFF3A1606.toInt()
        stroke.alpha = alpha
        stroke.strokeWidth = r * 0.16f
        c.drawCircle(x, y, r, stroke)
        c.save()
        c.rotate(rot, x, y)
        val rr = r * 0.62f
        stroke.color = Color.WHITE
        stroke.alpha = alpha
        stroke.strokeWidth = r * 0.2f
        stroke.strokeCap = Paint.Cap.ROUND
        dst.set(x - rr, y - rr, x + rr, y + rr)
        for (start in floatArrayOf(-80f, 100f)) {
            c.drawArc(dst, start, 130f, false, stroke)
            val a = ((start + 130f) * PI / 180f).toFloat()
            val ax = x + cos(a) * rr
            val ay = y + sin(a) * rr
            val tx = -sin(a)
            val ty = cos(a)
            path.reset()
            path.moveTo(ax + tx * r * 0.34f, ay + ty * r * 0.34f)
            path.lineTo(ax + cos(a) * r * 0.26f, ay + sin(a) * r * 0.26f)
            path.lineTo(ax - cos(a) * r * 0.26f, ay - sin(a) * r * 0.26f)
            path.close()
            fill.color = Color.WHITE
            fill.alpha = alpha
            c.drawPath(path, fill)
        }
        stroke.strokeCap = Paint.Cap.BUTT
        c.restore()
    }

    // ---- aim guide ---------------------------------------------------------------------

    private fun drawAimGuide(c: Canvas, game: Level5Game, now: Float) {
        if (!game.ball.visible || game.over || game.paused) return
        val opening = !game.aimTouched && game.introActive
        val aim = game.aimPath()
        if (!opening) drawPrediction(c, game, aim.hit, aim.matches, now)

        val pts = aim.points
        val bmp = assets.chevron.getValue(game.color)
        val track = spec.chevronTrack
        var s = track.first().first
        var k = 0
        val limit = if (opening) track.last().first + 1f else Float.MAX_VALUE
        val pop = ((now - game.ball.loadedAt) / 0.2f).coerceIn(0f, 1f)
        var prev: PathPoint? = null
        beam.color = game.color.glow
        while (s <= limit) {
            val pos = pointAt(pts, s) ?: break
            val (x, y, dx, dy, remaining) = pos
            val w = if (k < track.size) track[k].second else chevronWidthAt(y)
            if (remaining < w * 0.9f) break
            val scale = w / spec.chevronW
            val hw = bmp.width / spec.chevronScale / 2f * scale
            val hh = bmp.height / spec.chevronScale / 2f * scale
            val alpha = if (opening) 1f else 0.82f + 0.18f * cos(2f * PI.toFloat() * (now * 1.3f - k * 0.11f))
            prev?.let {
                // Soft beam between chevrons, like the painted guide's haze.
                beam.strokeWidth = w * 1.1f
                beam.alpha = (34 * pop).toInt()
                c.drawLine(it.x, it.y, x, y, beam)
                beam.strokeWidth = w * 0.45f
                beam.alpha = (46 * pop).toInt()
                c.drawLine(it.x, it.y, x, y, beam)
            }
            prev = PathPoint(x, y, dx, dy, remaining)
            bmpPaint.alpha = (255 * alpha * pop).toInt()
            c.save()
            c.rotate((atan2(dx, -dy) * 180f / PI).toFloat(), x, y)
            dst.set(x - hw, y - hh, x + hw, y + hh)
            c.drawBitmap(bmp, null, dst, bmpPaint)
            c.restore()
            k++
            s += if (k < track.size) track[k].first - track[k - 1].first else chevronSpacingAt(y)
        }
        bmpPaint.alpha = 255
    }

    /** Group the shot would clear, and what colour each neighbour turns into. */
    private fun drawPrediction(c: Canvas, game: Level5Game, hit: Int, matches: Boolean, now: Float) {
        if (hit < 0 || !matches) return
        val board = game.board
        val group = board.group(hit)
        val pulse = 0.5f + 0.5f * sin(now * 9f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4f + 2f * pulse
        stroke.alpha = (140 + 100 * pulse).toInt()
        for (i in group) {
            for (p in board.blocks[i].spec.parts) {
                if (p.w < 8f || p.h < 8f) continue
                dst.set(p.l + 2f, p.t + 2f, p.r - 2f, p.b - 2f)
                c.drawRoundRect(dst, 9f, 9f, stroke)
            }
        }
        for (i in board.neighboursOf(group)) {
            val r = board.blocks[i].spec.rect
            val next = board.blocks[i].color.next()
            val x = r.r - 13f
            val y = r.t + 13f
            val rad = 10f + 1.5f * pulse
            fill.shader = null
            fill.color = 0xFF2A1206.toInt()
            c.drawCircle(x, y, rad + 3.5f, fill)
            fill.color = Color.WHITE
            c.drawCircle(x, y, rad + 1.5f, fill)
            fill.color = next.swatch
            c.drawCircle(x, y, rad - 1f, fill)
        }
    }

    private data class PathPoint(val x: Float, val y: Float, val dx: Float, val dy: Float, val remaining: Float)

    /** Point [s] along the polyline, with its direction and the distance left to its end. */
    private fun pointAt(pts: List<FloatArray>, s: Float): PathPoint? {
        var acc = 0f
        var total = 0f
        for (i in 1 until pts.size) total += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        for (i in 1 until pts.size) {
            val ax = pts[i - 1][0]; val ay = pts[i - 1][1]
            val len = hypot(pts[i][0] - ax, pts[i][1] - ay)
            if (len <= 0f) continue
            if (acc + len >= s) {
                val u = (s - acc) / len
                val dx = (pts[i][0] - ax) / len
                val dy = (pts[i][1] - ay) / len
                return PathPoint(ax + dx * len * u, ay + dy * len * u, dx, dy, total - s)
            }
            acc += len
        }
        return null
    }

    private fun chevronWidthAt(y: Float): Float {
        val t = spec.chevronTrack
        val (s0, w0) = t.first()
        val (s1, w1) = t.last()
        val y0 = spec.ballY - s0
        val y1 = spec.ballY - s1
        return (w0 + (w1 - w0) * (y - y0) / (y1 - y0)).coerceIn(w1 * 0.8f, w0)
    }

    private fun chevronSpacingAt(y: Float): Float = chevronWidthAt(y) * 0.95f + 11f

    // ---- ball ------------------------------------------------------------------------

    private fun drawBall(c: Canvas, game: Level5Game, now: Float) {
        val b = game.ball
        if (game.phase == Phase.FLYING || game.phase == Phase.RETURNING) {
            trail.addFirst(floatArrayOf(b.x, b.y, b.r))
            while (trail.size > 7) trail.removeLast()
        } else trail.clear()
        if (!b.visible) return
        val s = b.r / spec.ballRadius
        val pop = if (now - b.loadedAt < 0.22f) 0.6f + 0.4f * smooth((now - b.loadedAt) / 0.22f) else 1f
        val glow = game.color.glow
        for ((i, t) in trail.withIndex()) {
            if (i == 0) continue
            val k = 1f - i / trail.size.toFloat()
            fill.shader = null
            fill.color = glow
            fill.alpha = (110 * k).toInt()
            c.drawCircle(t[0], t[1], t[2] * (0.55f + 0.35f * k), fill)
        }
        if (game.phase == Phase.FLYING || game.phase == Phase.RETURNING) {
            fill.shader = RadialGradient(b.x, b.y, b.r * 1.9f, intArrayOf(withAlpha(glow, 150), withAlpha(glow, 0)),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(b.x, b.y, b.r * 1.9f, fill)
            fill.shader = null
        }
        val box = spec.ballSpriteBox
        val hw = box.w / 2f * s * pop
        val hh = box.h / 2f * s * pop
        dst.set(b.x - hw, b.y - hh, b.x + hw, b.y + hh)
        c.drawBitmap(assets.ballHalo, null, dst, addPaint)
        c.drawBitmap(assets.ball.getValue(game.color), null, dst, bmpPaint)
    }

    // ---- HUD -----------------------------------------------------------------------

    private val hud = spec.hud

    private fun drawHud(c: Canvas, game: Level5Game, now: Float, dt: Float) {
        drawStars(c, game)

        // Timer: white on the dark pill beside the stopwatch.
        val secs = game.timerSeconds
        val timer = "%d:%02d".format(secs / 60, secs % 60)
        val bonus = (now - game.lastTimeBonusAt).let { if (it in 0f..0.5f) 1f - it / 0.5f else 0f }
        val low = secs <= 10 && !game.over
        val timerColor = when {
            bonus > 0f -> lerpColor(Color.WHITE, 0xFF8CFF6A.toInt(), bonus)
            low -> lerpColor(Color.WHITE, 0xFFFF5A48.toInt(), 0.5f + 0.5f * sin(now * 10f))
            else -> Color.WHITE
        }
        val t = hud.timer
        whiteText(c, timer, t.x, t.y, t.size * (1f + 0.1f * bonus), timerColor)

        // Score counts up.
        if (shownScore < 0f) shownScore = game.score.toFloat()
        shownScore = approach(shownScore, game.score.toFloat(), dt, 5000f)
        creamText(c, "%,d".format(shownScore.roundToInt()), hud.score)

        // Coins: right-aligned against the painted coin.
        if (shownCoins < 0f) shownCoins = game.coins.toFloat()
        shownCoins = approach(shownCoins, game.coins.toFloat(), dt, 40f)
        val k = hud.coins
        goldText(c, "+${shownCoins.roundToInt()}", k.x + k.width, k.y, k.size, Paint.Align.RIGHT, 0f, rim = false)

        // Combo: pops when a clear raises it (timed from the clear, not from drawing).
        if (game.combo >= 2) {
            val kc = ((now - game.lastClearAt) / 0.3f).coerceIn(0f, 1f)
            val pop = 1f + 0.22f * (1f - smooth(kc))
            val cb = hud.comboBox
            c.save()
            c.scale(pop, pop, cb.cx, cb.cy)
            val w = hud.combo
            goldText(c, "Combo", w.x, w.y, w.size, Paint.Align.LEFT, w.angle, w.scaleX)
            drawComboNumber(c, "x${game.combo}")
            c.restore()
        }
    }

    /**
     * "x9" as painted. Longer numbers ("x10", "x26") keep the painted number's size
     * until they would leave the combo box, then shrink to fit, so the combo never
     * reaches the blocks or the screen edge.
     */
    private fun drawComboNumber(c: Canvas, s: String) {
        val n = hud.comboNumber
        val cb = hud.comboBox
        val centre = n.x + n.width / 2f
        val w = measure(s, n.size) * n.scaleX
        val room = 2f * min(centre - cb.l, cb.r - centre)
        val size = if (w <= room) n.size else n.size * room / w
        goldText(c, s, centre, n.y - (n.size - size) * 0.3f, size, Paint.Align.CENTER, n.angle, n.scaleX)
    }

    private fun drawStars(c: Canvas, game: Level5Game) {
        // The plate already shows two gold stars and one empty; only draw what differs.
        for (i in spec.starBoxes.indices) {
            val want = i < game.stars
            val baked = i < 2
            if (want == baked) continue
            val box = spec.starBoxes[i]
            val bmp = if (want) assets.starGold else assets.starEmpty
            dst.set(box.cx - bmp.width / 2f, box.cy - bmp.height / 2f, box.cx + bmp.width / 2f, box.cy + bmp.height / 2f)
            c.drawBitmap(assets.starEmpty, null, dst, bmpPaint)
            c.drawBitmap(bmp, null, dst, bmpPaint)
        }
    }

    private fun whiteText(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, scaleX: Float = 1f,
    ) = hudText.white(c, s, x, y, size, color, align, scaleX)

    private fun creamText(c: Canvas, s: String, spot: TextSpot) = hudText.cream(c, s, spot)

    private fun goldText(
        c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align, angle: Float, scaleX: Float = 1f,
        rim: Boolean = true,
    ) = hudText.gold(c, s, x, y, size, align, angle, scaleX, rim)

    // ---- Goal panel ------------------------------------------------------------------

    /**
     * Fills the painted Goal board: the instruction, then the colour cycle as five mini
     * blocks in order (touching blocks step one to the right; the last wraps to the
     * first). The loaded ball's colour is outlined; colours no longer on the board dim.
     */
    private fun drawGoalPanel(c: Canvas, game: Level5Game, now: Float) {
        val g = hud.goalText
        val x0 = g.l + 9f
        whiteText(c, "Hit the blocks", x0, g.t + 32f, 26f, Color.WHITE, scaleX = 0.92f)
        whiteText(c, "and clear them", x0, g.t + 63f, 26f, Color.WHITE, scaleX = 0.92f)
        whiteText(c, "all!", x0, g.t + 94f, 26f, Color.WHITE, scaleX = 0.92f)

        val present = game.board.colorsPresent()
        val n = GameColor.entries.size
        val size = 28f
        val gap = (g.w - 6f - n * size) / (n - 1)
        val y = g.b - size - 8f
        var x = g.l + 3f
        val icon = swatchSource
        for ((i, col) in GameColor.entries.withIndex()) {
            val on = col in present
            dst.set(x, y, x + size, y + size)
            if (on) c.drawBitmap(assets.blocks.getValue(col), icon, dst, bmpPaint)
            else {
                fadePaint.alpha = 90
                c.drawBitmap(assets.blocks.getValue(col), icon, dst, fadePaint)
                fadePaint.alpha = 255
            }
            if (col == game.color && !game.over) {
                val pulse = 0.5f + 0.5f * sin(now * 6f)
                stroke.color = Color.WHITE
                stroke.strokeWidth = 2.5f + pulse
                dst.set(x - 3f, y - 3f, x + size + 3f, y + size + 3f)
                c.drawRoundRect(dst, 7f, 7f, stroke)
            }
            if (i < n - 1) {
                fill.shader = null
                fill.color = 0xE6FFE3A8.toInt()
                val ax = x + size + gap / 2f
                val ay = y + size / 2f
                path.reset()
                path.moveTo(ax - 2.5f, ay - 4.5f)
                path.lineTo(ax + 3f, ay)
                path.lineTo(ax - 2.5f, ay + 4.5f)
                path.close()
                c.drawPath(path, fill)
            }
            x += size + gap
        }
    }

    /** Atlas rect of one block (the central blue sparkle block) used as the swatch icon. */
    private val swatchSource: Rect = spec.blocks.first { it.id == "b19" }.rect.let {
        Rect((it.l - spec.atlasX).toInt(), (it.t - spec.atlasY).toInt(), (it.r - spec.atlasX).toInt(), (it.b - spec.atlasY).toInt())
    }

    // ---- overlays --------------------------------------------------------------------

    private fun drawPaused(c: Canvas) {
        c.drawColor(0x8C000000.toInt())
        val cx = spec.stageW / 2f
        val cy = spec.stageH * 0.42f
        goldText(c, "Paused", cx, cy, 96f, Paint.Align.CENTER, 0f)
        whiteText(c, "Tap to resume", cx, cy + 76f, 40f, Color.WHITE, Paint.Align.CENTER)
    }

    private fun drawEndCard(c: Canvas, game: Level5Game, title: String, hint: String) {
        c.drawColor(0x99000000.toInt())
        val cx = spec.stageW / 2f
        val cy = spec.stageH * 0.4f
        goldText(c, title, cx, cy, 78f, Paint.Align.CENTER, 0f)
        for (i in 0 until 3) {
            val bmp = if (i < game.stars) assets.starGold else assets.starEmpty
            val sx = cx + (i - 1) * 100f
            dst.set(sx - bmp.width * 0.55f, cy + 36f, sx + bmp.width * 0.55f, cy + 36f + bmp.height * 1.1f)
            c.drawBitmap(bmp, null, dst, bmpPaint)
        }
        whiteText(c, "Score %,d".format(game.score), cx, cy + 200f, 52f, Color.WHITE, Paint.Align.CENTER)
        whiteText(c, hint, cx, cy + 270f, 38f, Color.WHITE, Paint.Align.CENTER)
    }

    private fun measure(s: String, size: Float): Float = hudText.measure(s, size)

    // ---- helpers ---------------------------------------------------------------------

    private fun smooth(k: Float) = k * k * (3f - 2f * k)
    private fun gray(k: Float): Int = (255 * k.coerceIn(0f, 1f)).toInt().let { (it shl 16) or (it shl 8) or it }
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun darken(c: Int, k: Float) = Color.rgb(
        (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt(),
    )

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    private fun approach(v: Float, target: Float, dt: Float, rate: Float): Float {
        if (abs(target - v) < 0.5f) return target
        val step = max(rate * dt, abs(target - v) * min(1f, dt * 8f))
        return if (target > v) min(target, v + step) else max(target, v - step)
    }

    companion object {
        const val SHIFT_TIME = 0.42f
        const val BREAK_TIME = 0.34f
        const val INTRO_FX = 1.0f
    }
}
