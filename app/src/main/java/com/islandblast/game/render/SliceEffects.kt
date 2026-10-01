package com.islandblast.game.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.islandblast.game.model.SliceEvent
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.TargetKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * What a cut looks like: the treasure splits along the blade's line into two halves of
 * its own art that fly apart; gems throw crystal shards, wood throws splinters, coins
 * fly into the score panel, chests crack and burst. Driven by game time, so it
 * freezes with the pause and replays deterministically in tests.
 */
class SliceEffects(private val assets: SliceAssets, seed: Int = 4) {
    private val rnd = Random(seed)
    private val spec = assets.spec
    private val hudText = HudText(assets.fredoka)

    /** One side of a cut treasure: its sprite clipped to a half-plane, flying free. */
    private class Half(
        val sprite: Int, val scale: Float,
        /** The cut line in sprite coordinates, and which side of it this half keeps. */
        val lx: Float, val ly: Float, val ldx: Float, val ldy: Float, val side: Float,
        /** The point of the sprite the half turns about, and where that point starts on screen. */
        val px: Float, val py: Float, val x: Float, val y: Float,
        val vx: Float, val vy: Float, val rot: Float, val spin: Float, val start: Float,
    )
    private class Shard(val x: Float, val y: Float, val vx: Float, val vy: Float, val size: Float, val color: Int,
                        val rot: Float, val spin: Float, val wood: Boolean, val start: Float, val life: Float)
    private class Coin(val sprite: Int, val x: Float, val y: Float, val scale: Float, val start: Float)
    private class Flash(val x: Float, val y: Float, val radius: Float, val color: Int, val start: Float)
    private class Slash(val x: Float, val y: Float, val dx: Float, val dy: Float, val len: Float, val start: Float)
    private class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Int, val size: Float,
                        val start: Float, val life: Float)
    private class Popup(val text: String, val x: Float, val y: Float, val style: Int, val size: Float, val start: Float,
                        val life: Float)
    private class Ring(val x: Float, val y: Float, val radius: Float, val color: Int, val start: Float, val life: Float,
                       val width: Float, val flat: Float)
    private class Drop(val x: Float, val y: Float, val vx: Float, val vy: Float, val size: Float, val start: Float)

    private val halves = ArrayList<Half>()
    private val shards = ArrayList<Shard>()
    private val coins = ArrayList<Coin>()
    private val flashes = ArrayList<Flash>()
    private val slashes = ArrayList<Slash>()
    private val sparks = ArrayList<Spark>()
    private val popups = ArrayList<Popup>()
    private val rings = ArrayList<Ring>()
    private val drops = ArrayList<Drop>()

    private val plain = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val clip = Path()
    private val poly = Path()

    private val scoreX = spec.hud.score.x + spec.hud.score.width / 2f
    private val scoreY = spec.hud.score.y - spec.hud.score.size * 0.35f

    fun consume(game: SliceGame) {
        val now = game.time
        for (e in game.events) when (e) {
            is SliceEvent.Cut -> cut(e, now)
            is SliceEvent.ChestHit -> chestHit(e, now)
            is SliceEvent.Tossed -> if (!e.piece.bonus) splash(e.piece.x, SliceGame.TOSS_Y + 20f, 1f, now)
            is SliceEvent.Dropped -> {
                splash(e.piece.x, SliceGame.DROP_Y - 10f, 0.8f, now)
                if (e.comboLost && game.chain == 0) {
                    popups += Popup("Missed!", e.piece.x.coerceIn(140f, spec.stageW - 140f), SliceGame.DROP_Y - 70f,
                        STYLE_MISS, 38f, now, 0.8f)
                }
            }
            is SliceEvent.SwipeBonus -> {
                val word = when {
                    e.cuts >= 7 -> "Mystic!"
                    e.cuts >= 5 -> "Awesome!"
                    else -> "Great!"
                }
                val x = e.x.coerceIn(220f, spec.stageW - 220f)
                val y = e.y.coerceIn(420f, 1100f)
                popups += Popup(word, x, y - 40f, STYLE_WORD, 84f, now, 1.0f)
                popups += Popup("+%,d".format(e.points), x, y + 40f, STYLE_GOLD, 46f, now + 0.1f, 0.9f)
                repeat(18) { spark(x, y - 40f, assets.glow[rnd.nextInt(assets.glow.size)], now, 200f, 700f, 5f) }
            }
            is SliceEvent.TimeBonus -> {
                val t = spec.hud.timer
                popups += Popup("+%ds".format(e.seconds.toInt()), e.x, e.y - 60f, STYLE_TIME, 50f, now + 0.05f, 0.9f)
                rings += Ring(t.x + 40f, t.y - 20f, 70f, 0xFF8CFF6A.toInt(), now, 0.45f, 7f, 1f)
            }
            is SliceEvent.StarEarned -> {
                val box = spec.hud.stars[(e.stars - 1).coerceIn(0, 2)]
                rings += Ring(box.cx, box.cy, 70f, 0xFFFFE27A.toInt(), now, 0.5f, 8f, 1f)
                repeat(16) { spark(box.cx, box.cy, 0xFFFFD84A.toInt(), now, 120f, 420f, 4f) }
                if (e.stars == 1) popups += Popup("Goal reached!", spec.stageW / 2f, 360f, STYLE_WORD, 64f, now, 1.3f)
            }
            // The player who swiped already knows; "Go!" is for a clock that starts by itself.
            is SliceEvent.Started -> if (!e.bySwipe) {
                popups += Popup("Go!", spec.stageW / 2f, spec.stageH * 0.42f, STYLE_WORD, 140f, now + 0.2f, 0.8f)
            }
            is SliceEvent.Won -> repeat(60) {
                spark(spec.stageW / 2f + (rnd.nextFloat() - 0.5f) * spec.stageW * 0.6f, spec.stageH * 0.4f,
                    assets.glow[rnd.nextInt(assets.glow.size)], now, 300f, 1000f, 5f)
            }
            is SliceEvent.Lost -> Unit
        }
        game.events.clear()
        prune(now)
    }

    private fun cut(e: SliceEvent.Cut, now: Float) {
        val p = e.piece
        val s = p.spec
        val color = assets.glow[p.sprite]
        // Two halves fly apart, each pushed away from the cut.
        val nx = -e.ldy
        val ny = e.ldx
        val wnx = -e.dy
        val wny = e.dx
        val off = s.radius * 0.35f
        val a = p.rot * DEG
        for (side in floatArrayOf(1f, -1f)) {
            val px = e.lx + nx * side * off
            val py = e.ly + ny * side * off
            // Where that sprite point is on screen now.
            val rx = (px - s.cx) * p.scale
            val ry = (py - s.cy) * p.scale
            val x = p.x + rx * cos(a) - ry * sin(a)
            val y = p.y + rx * sin(a) + ry * cos(a)
            val push = 260f + rnd.nextFloat() * 120f
            halves += Half(p.sprite, p.scale, e.lx, e.ly, e.ldx, e.ldy, side, px, py, x, y,
                p.vx * 0.6f + wnx * side * push + e.dx * 90f, min(p.vy * 0.4f, 0f) - 260f + wny * side * push * 0.6f,
                p.rot, p.spin * 0.5f + side * (160f + rnd.nextFloat() * 160f), now)
        }
        val r = p.radius
        slashes += Slash(e.wx, e.wy, e.dx, e.dy, r * 2.6f + 40f, now)
        flashes += Flash(e.wx, e.wy, r * 1.6f + 30f, color, now)
        repeat(if (r > 45f) 14 else 9) { spark(e.wx, e.wy, color, now, 160f, 620f, 4f) }
        when (p.kind) {
            TargetKind.GEM -> repeat(if (r > 45f) 10 else 6) { shard(e.wx, e.wy, color, r, false, now) }
            TargetKind.COIN -> coins += Coin(p.sprite, p.x, p.y, 0.55f * p.scale, now + 0.1f)
            TargetKind.BARREL, TargetKind.CRATE -> repeat(8) { shard(e.wx, e.wy, WOOD, r, true, now) }
            TargetKind.CHEST -> {
                flashes += Flash(p.x, p.y, r * 2.4f + 60f, 0xFFFFD84A.toInt(), now)
                rings += Ring(p.x, p.y, r * 1.8f + 40f, 0xFFFFE27A.toInt(), now, 0.4f, 9f, 1f)
                repeat(12) { shard(p.x, p.y, WOOD, r, true, now) }
                repeat(20) { spark(p.x, p.y, 0xFFFFD84A.toInt(), now, 220f, 820f, 5f) }
            }
        }
        val label = if (e.multiplier >= 2) "+%,d".format(e.points) else "+${e.points}"
        popups += Popup(label, e.wx, e.wy - r * 0.5f - 10f, STYLE_GOLD, if (e.points >= 100) 50f else 42f, now, 0.8f)
    }

    private fun chestHit(e: SliceEvent.ChestHit, now: Float) {
        val p = e.piece
        flashes += Flash(e.wx, e.wy, p.radius + 40f, 0xFFFFE9A0.toInt(), now)
        slashes += Slash(e.wx, e.wy, e.dx, e.dy, p.radius * 2.4f, now)
        repeat(6) { shard(e.wx, e.wy, WOOD, p.radius, true, now) }
        repeat(10) { spark(e.wx, e.wy, 0xFFFFD84A.toInt(), now, 140f, 520f, 4f) }
        popups += Popup("+${e.points}", e.wx, e.wy - 50f, STYLE_GOLD, 38f, now, 0.7f)
    }

    private fun splash(x: Float, y: Float, k: Float, now: Float) {
        rings += Ring(x, y, 70f * k, 0xFFDFF8FF.toInt(), now, 0.45f, 6f, 0.32f)
        repeat((10 * k).toInt()) {
            val a = (-PI / 2 + (rnd.nextFloat() - 0.5f) * 1.6f).toFloat()
            val sp = (240f + rnd.nextFloat() * 380f) * k
            drops += Drop(x + (rnd.nextFloat() - 0.5f) * 40f, y, cos(a) * sp, sin(a) * sp, 5f + rnd.nextFloat() * 5f, now)
        }
    }

    private fun shard(x: Float, y: Float, color: Int, r: Float, wood: Boolean, now: Float) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val sp = 200f + rnd.nextFloat() * 420f
        val size = (if (wood) 10f else 8f) + rnd.nextFloat() * r * 0.18f
        shards += Shard(x, y, cos(a) * sp, sin(a) * sp - 220f, size, color, rnd.nextFloat() * 360f,
            (rnd.nextFloat() - 0.5f) * 900f, wood, now, 0.7f + rnd.nextFloat() * 0.3f)
    }

    private fun spark(x: Float, y: Float, color: Int, t0: Float, vMin: Float, vMax: Float, size: Float) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val sp = vMin + rnd.nextFloat() * (vMax - vMin)
        sparks += Spark(x, y, cos(a) * sp, sin(a) * sp, color, size * (0.6f + rnd.nextFloat() * 0.8f), t0,
            0.35f + rnd.nextFloat() * 0.3f)
    }

    /** Drops finished effects. Runs every frame, drawn or not. */
    private fun prune(now: Float) {
        halves.removeAll { now - it.start > HALF_LIFE }
        shards.removeAll { now - it.start > it.life }
        coins.removeAll { now - it.start > COIN_LIFE }
        flashes.removeAll { now - it.start > FLASH_LIFE }
        slashes.removeAll { now - it.start > SLASH_LIFE }
        sparks.removeAll { now - it.start > it.life }
        popups.removeAll { now - it.start > it.life }
        rings.removeAll { now - it.start > it.life }
        drops.removeAll { now - it.start > DROP_LIFE }
    }

    val busy: Boolean
        get() = halves.isNotEmpty() || shards.isNotEmpty() || coins.isNotEmpty() || flashes.isNotEmpty() ||
            sparks.isNotEmpty() || popups.isNotEmpty() || rings.isNotEmpty() || drops.isNotEmpty()

    /** Halves of cut treasure still flying (tests check cuts are visible). */
    val halvesInFlight: Int get() = halves.size

    fun clear() {
        halves.clear(); shards.clear(); coins.clear(); flashes.clear(); slashes.clear(); sparks.clear()
        popups.clear(); rings.clear(); drops.clear()
    }

    /** Behind the HUD: halves, shards, splashes, flashes. */
    fun drawWorld(c: Canvas, now: Float) {
        for (r in rings) {
            if (now < r.start) continue
            val k = ((now - r.start) / r.life).coerceIn(0f, 1f)
            ringPaint.color = r.color
            ringPaint.alpha = (255 * (1f - k)).toInt()
            ringPaint.strokeWidth = r.width * (1f - 0.6f * k)
            val rad = r.radius * (0.35f + 0.65f * easeOut(k))
            c.drawOval(r.x - rad, r.y - rad * r.flat, r.x + rad, r.y + rad * r.flat, ringPaint)
        }
        for (h in halves) drawHalf(c, h, now)
        plain.alpha = 255
        for (s in shards) {
            if (now < s.start) continue
            val t = now - s.start
            val k = t / s.life
            c.save()
            c.translate(s.x + s.vx * t, s.y + s.vy * t + 0.5f * GRAVITY * t * t)
            c.rotate(s.rot + s.spin * t)
            val a = (255 * min(1f, (1f - k) / 0.35f)).toInt()
            poly.reset()
            if (s.wood) {
                poly.moveTo(-s.size, -s.size * 0.22f)
                poly.lineTo(s.size, -s.size * 0.12f)
                poly.lineTo(s.size * 0.8f, s.size * 0.22f)
                poly.lineTo(-s.size * 0.9f, s.size * 0.18f)
                poly.close()
                fill.color = withAlpha(0xFF6B3E17.toInt(), a)
                c.drawPath(poly, fill)
                fill.color = withAlpha(0xFFC98A47.toInt(), a)
                c.drawRect(-s.size * 0.8f, -s.size * 0.14f, s.size * 0.8f, s.size * 0.02f, fill)
            } else {
                poly.moveTo(0f, -s.size)
                poly.lineTo(s.size * 0.7f, s.size * 0.5f)
                poly.lineTo(-s.size * 0.6f, s.size * 0.6f)
                poly.close()
                fill.color = withAlpha(s.color, a)
                c.drawPath(poly, fill)
                fill.color = withAlpha(Color.WHITE, (a * 0.8f).toInt())
                c.drawCircle(-s.size * 0.1f, -s.size * 0.2f, s.size * 0.22f, fill)
            }
            c.restore()
        }
        for (d in drops) {
            if (now < d.start) continue
            val t = now - d.start
            val k = t / DROP_LIFE
            fill.color = withAlpha(0xFFE6FAFF.toInt(), (230 * (1f - k)).toInt())
            c.drawCircle(d.x + d.vx * t, d.y + d.vy * t + 0.5f * GRAVITY * t * t, d.size * (1f - 0.4f * k), fill)
        }
        for (f in flashes) {
            if (now < f.start) continue
            val k = (now - f.start) / FLASH_LIFE
            val r = f.radius * (0.55f + 0.7f * easeOut(k))
            val a = (1f - k) * (1f - k)
            add.shader = RadialGradient(f.x, f.y, r, intArrayOf(
                withAlpha(Color.WHITE, (255 * a).toInt()), withAlpha(f.color, (190 * a).toInt()), 0,
            ), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(f.x, f.y, r, add)
            add.shader = null
        }
        // The blade's light along each cut, through the treasure.
        for (s in slashes) {
            if (now < s.start) continue
            val k = (now - s.start) / SLASH_LIFE
            val half = s.len / 2f * (0.6f + 0.6f * easeOut(k))
            val a = 1f - k
            for ((w, col) in arrayOf(26f to 0xFF56C8FF.toInt(), 12f to 0xFFBFF2FF.toInt(), 5f to Color.WHITE)) {
                line.color = withAlpha(col, (230 * a).toInt())
                line.strokeWidth = w * (1f - 0.5f * k)
                c.drawLine(s.x - s.dx * half, s.y - s.dy * half, s.x + s.dx * half, s.y + s.dy * half, line)
            }
        }
        for (p in sparks) {
            if (now < p.start) continue
            val t = now - p.start
            val k = t / p.life
            val x = p.x + p.vx * t
            val y = p.y + p.vy * t + 0.25f * GRAVITY * t * t
            add.color = withAlpha(p.color, (200 * (1f - k)).toInt())
            c.drawCircle(x, y, p.size * 1.8f * (1f - 0.5f * k), add)
            add.color = withAlpha(Color.WHITE, (255 * (1f - k)).toInt())
            c.drawCircle(x, y, p.size * 0.7f * (1f - 0.5f * k), add)
        }
    }

    private fun drawHalf(c: Canvas, h: Half, now: Float) {
        if (now < h.start) return
        val t = now - h.start
        val k = t / HALF_LIFE
        val bmp = assets.targets[h.sprite]
        val s = spec.targets[h.sprite]
        c.save()
        c.translate(h.x + h.vx * t, h.y + h.vy * t + 0.5f * GRAVITY * t * t)
        c.rotate(h.rot + h.spin * t)
        c.scale(h.scale, h.scale)
        c.translate(-h.px, -h.py)
        // Keep only this side of the cut.
        val big = max(bmp.width, bmp.height) * 2f
        val nx = -h.ldy * h.side
        val ny = h.ldx * h.side
        clip.reset()
        clip.moveTo(h.lx - h.ldx * big, h.ly - h.ldy * big)
        clip.lineTo(h.lx + h.ldx * big, h.ly + h.ldy * big)
        clip.lineTo(h.lx + h.ldx * big + nx * big, h.ly + h.ldy * big + ny * big)
        clip.lineTo(h.lx - h.ldx * big + nx * big, h.ly - h.ldy * big + ny * big)
        clip.close()
        c.clipPath(clip)
        plain.alpha = (255 * min(1f, (1f - k) / 0.3f)).toInt()
        c.drawBitmap(bmp, s.box.l, s.box.t, plain)
        // A bright edge where the blade went through, fading.
        if (t < 0.25f) {
            line.color = withAlpha(Color.WHITE, (200 * (1f - t / 0.25f)).toInt())
            line.strokeWidth = 6f / h.scale
            c.drawLine(h.lx - h.ldx * big, h.ly - h.ldy * big, h.lx + h.ldx * big, h.ly + h.ldy * big, line)
        }
        c.restore()
    }

    /** Over the HUD: coins flying into the score, point popups, words. */
    fun drawOverlay(c: Canvas, now: Float) {
        for (co in coins) {
            if (now < co.start) continue
            val k = ((now - co.start) / COIN_LIFE).coerceIn(0f, 1f)
            val q = k * k
            val x = co.x + (scoreX - co.x) * q
            val y = co.y + (scoreY - co.y) * q - sin(q * PI.toFloat()) * 120f
            val bmp = assets.targets[co.sprite]
            val sc = co.scale * (1f - 0.4f * k)
            c.save()
            c.translate(x, y)
            c.scale(sc, sc)
            plain.alpha = (255 * min(1f, (1f - k) / 0.15f)).toInt()
            c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, plain)
            c.restore()
        }
        plain.alpha = 255
        for (p in popups) {
            if (now < p.start) continue
            val k = (now - p.start) / p.life
            val a = (255 * min(1f, (1f - k) / 0.3f)).toInt()
            val rise = if (p.style == STYLE_WORD) 30f else 70f
            val y = p.y - rise * easeOut(k)
            val pop = 1f + 0.3f * (1f - easeOut(min(1f, k * 4f)))
            val size = p.size * pop
            c.saveLayerAlpha(p.x - 420f, y - size * 1.4f, p.x + 420f, y + size * 0.6f, a)
            when (p.style) {
                STYLE_TIME -> hudText.white(c, p.text, p.x, y, size, 0xFF8CFF6A.toInt(), Paint.Align.CENTER)
                STYLE_MISS -> hudText.white(c, p.text, p.x, y, size, 0xFFFF7A6A.toInt(), Paint.Align.CENTER)
                STYLE_WORD -> hudText.gold(c, p.text, p.x, y, size, Paint.Align.CENTER, 0f)
                else -> hudText.gold(c, p.text, p.x, y, size, Paint.Align.CENTER, 0f, rim = false)
            }
            c.restore()
        }
    }

    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k)
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    companion object {
        private const val GRAVITY = 1500f
        private const val HALF_LIFE = 1.3f
        private const val COIN_LIFE = 0.7f
        private const val FLASH_LIFE = 0.32f
        private const val SLASH_LIFE = 0.2f
        private const val DROP_LIFE = 0.6f
        private const val DEG = (PI / 180.0).toFloat()
        private const val WOOD = 0xFF8A5A2B.toInt()
        private const val STYLE_GOLD = 0
        private const val STYLE_TIME = 1
        private const val STYLE_WORD = 2
        private const val STYLE_MISS = 3

    }
}
