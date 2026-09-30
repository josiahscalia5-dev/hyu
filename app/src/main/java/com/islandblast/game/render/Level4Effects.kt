package com.islandblast.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.islandblast.game.model.Level4Event
import com.islandblast.game.model.Level4Game
import com.islandblast.game.model.TargetKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Harvest feedback for Level 4: treasure bursts into wedges of its own art, coins and
 * chest loot fly into the score panel, points pop, barrels add time. Driven by game
 * time so it freezes with the pause and replays deterministically in tests.
 */
class Level4Effects(private val assets: Level4Assets, seed: Int = 4) {
    private val rnd = Random(seed)
    private val spec = assets.spec
    private val hudText = HudText(assets.fredoka)

    /** A wedge of a target's sprite (angles in radians around its centre). */
    private class Chunk(
        val sprite: Int, val a0: Float, val a1: Float, val x: Float, val y: Float, val rot: Float,
        val vx: Float, val vy: Float, val spin: Float, val start: Float, val life: Float,
    )
    /** A sprite (whole) that flies into the score panel. */
    private class Loot(
        val sprite: Int, val x: Float, val y: Float, val burstX: Float, val burstY: Float, val scale: Float,
        val start: Float, val life: Float,
    )
    private class Flash(val x: Float, val y: Float, val radius: Float, val color: Int, val start: Float)
    private class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Int, val size: Float,
                        val start: Float, val life: Float)
    private class Popup(val text: String, val x: Float, val y: Float, val time: Boolean, val start: Float)
    private class Ring(val x: Float, val y: Float, val radius: Float, val color: Int, val start: Float,
                       val life: Float, val width: Float)

    private val chunks = ArrayList<Chunk>()
    private val loot = ArrayList<Loot>()
    private val flashes = ArrayList<Flash>()
    private val sparks = ArrayList<Spark>()
    private val popups = ArrayList<Popup>()
    private val rings = ArrayList<Ring>()

    private val plain = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wedge = Path()
    private val hover = FloatArray(3)

    private val coinSprites = spec.targets.indices.filter { spec.targets[it].kind == TargetKind.COIN }
    private val smallGems = spec.targets.indices.filter { spec.targets[it].kind == TargetKind.GEM && spec.targets[it].radius < 32f }
    private val scoreX = spec.hud.score.x + spec.hud.score.width / 2f
    private val scoreY = spec.hud.score.y - spec.hud.score.size * 0.35f

    fun consume(game: Level4Game) {
        val now = game.time
        for (e in game.events) when (e) {
            is Level4Event.Hit -> harvest(game, e, now)
            is Level4Event.Fired -> {
                val tip = game.tipFor(game.launcherTurn)
                rings += Ring(tip[0], tip[1], 60f, 0xFF9FE8FF.toInt(), now, 0.25f, 6f)
            }
            is Level4Event.Missed -> {
                val b = game.bolt
                if (b != null) {
                    val p = FloatArray(2)
                    b.path.at(b.end, p)
                    repeat(8) { spark(p[0], p[1], 0xFF9FE8FF.toInt(), now, 80f, 260f, 3f) }
                }
            }
            is Level4Event.StarEarned -> {
                val box = spec.hud.stars[(e.stars - 1).coerceIn(0, 2)]
                rings += Ring(box.cx, box.cy, 70f, 0xFFFFE27A.toInt(), now, 0.5f, 8f)
                repeat(14) { spark(box.cx, box.cy, 0xFFFFD84A.toInt(), now, 120f, 380f, 4f) }
            }
            is Level4Event.Won -> repeat(50) {
                spark(spec.stageW / 2f + (rnd.nextFloat() - 0.5f) * spec.stageW * 0.5f, spec.stageH * 0.42f,
                    assets.glow[rnd.nextInt(assets.glow.size)], now, 300f, 950f, 5f)
            }
            else -> Unit
        }
        game.events.clear()
        prune(now)
    }

    private fun harvest(game: Level4Game, e: Level4Event.Hit, now: Float) {
        val t = game.treasures[e.target]
        val s = t.spec
        game.hover(t, hover)
        val cx = s.cx + hover[0]
        val cy = s.cy + hover[1]
        val color = assets.glow[e.target]
        flashes += Flash(e.x, e.y, s.radius * 1.9f + 30f, color, now)
        rings += Ring(cx, cy, s.radius * 1.5f + 20f, color, now, 0.3f, 7f)
        repeat(if (s.radius > 40f) 16 else 9) { spark(cx, cy, color, now, 180f, 640f, 4.2f) }
        when (s.kind) {
            TargetKind.COIN -> loot += Loot(e.target, cx, cy, cx, cy - 40f, 1f, now, 0.6f)
            TargetKind.CHEST -> {
                shatter(e.target, cx, cy, hover[2], 9, now)
                repeat(10) {
                    val pool = if (it % 2 == 0 || smallGems.isEmpty()) coinSprites else smallGems
                    val a = -PI.toFloat() / 2f + (rnd.nextFloat() - 0.5f) * 2.6f
                    val r = 90f + rnd.nextFloat() * 110f
                    loot += Loot(pool[rnd.nextInt(pool.size)], cx, cy, cx + cos(a) * r, cy + sin(a) * r,
                        if (pool === coinSprites) 0.38f else 0.9f, now + it * 0.03f, 0.85f)
                }
            }
            else -> shatter(e.target, cx, cy, hover[2], if (s.radius > 45f) 8 else 5, now)
        }
        popups += Popup("+%,d".format(e.points), cx, cy - s.radius * 0.4f, false, now)
        if (s.kind == TargetKind.BARREL) {
            popups += Popup("+%ds".format((game.rules.barrelTimeBonus + game.rules.timeBonusPerHit).toInt()),
                cx, cy + s.radius * 0.3f, true, now + 0.1f)
        }
    }

    private fun shatter(sprite: Int, x: Float, y: Float, rot: Float, n: Int, now: Float) {
        val base = rnd.nextFloat() * 2f * PI.toFloat()
        val step = 2f * PI.toFloat() / n
        for (i in 0 until n) {
            val a0 = base + i * step
            val a1 = a0 + step
            val mid = (a0 + a1) / 2f
            val sp = 220f + rnd.nextFloat() * 260f
            chunks += Chunk(sprite, a0, a1, x, y, rot, cos(mid) * sp, sin(mid) * sp - 160f,
                (rnd.nextFloat() - 0.5f) * 540f, now, 0.7f + rnd.nextFloat() * 0.25f)
        }
    }

    private fun spark(x: Float, y: Float, color: Int, t0: Float, vMin: Float, vMax: Float, size: Float) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val sp = vMin + rnd.nextFloat() * (vMax - vMin)
        sparks += Spark(x, y, cos(a) * sp, sin(a) * sp, color, size * (0.6f + rnd.nextFloat() * 0.8f),
            t0, 0.35f + rnd.nextFloat() * 0.3f)
    }

    /** Drops finished effects. Runs every frame, drawn or not. */
    private fun prune(now: Float) {
        chunks.removeAll { now - it.start > it.life }
        loot.removeAll { now - it.start > it.life }
        flashes.removeAll { now - it.start > FLASH_LIFE }
        sparks.removeAll { now - it.start > it.life }
        popups.removeAll { now - it.start > POPUP_LIFE }
        rings.removeAll { now - it.start > it.life }
    }

    val busy get() = chunks.isNotEmpty() || loot.isNotEmpty() || flashes.isNotEmpty() || sparks.isNotEmpty() ||
        popups.isNotEmpty() || rings.isNotEmpty()

    fun clear() {
        chunks.clear(); loot.clear(); flashes.clear(); sparks.clear(); popups.clear(); rings.clear()
    }

    fun draw(c: Canvas, now: Float) {
        for (f in flashes) {
            if (now < f.start) continue
            val k = (now - f.start) / FLASH_LIFE
            val r = f.radius * (0.55f + 0.7f * easeOut(k))
            val a = (1f - k) * (1f - k)
            add.shader = RadialGradient(f.x, f.y, r, intArrayOf(
                withAlpha(Color.WHITE, (255 * a).toInt()), withAlpha(f.color, (200 * a).toInt()), 0,
            ), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(f.x, f.y, r, add)
            add.shader = null
            // Four-point star like the painted burst.
            add.color = withAlpha(Color.WHITE, (230 * a).toInt())
            val len = r * 1.3f
            c.save()
            c.rotate(45f * k, f.x, f.y)
            for (q in 0 until 4) {
                c.rotate(90f, f.x, f.y)
                c.drawOval(f.x - len, f.y - r * 0.07f, f.x + len * 0.02f, f.y + r * 0.07f, add)
            }
            c.restore()
        }

        for (r in rings) {
            if (now < r.start) continue
            val k = ((now - r.start) / r.life).coerceIn(0f, 1f)
            ringPaint.color = r.color
            ringPaint.alpha = (255 * (1f - k)).toInt()
            ringPaint.strokeWidth = r.width * (1f - 0.6f * k)
            c.drawCircle(r.x, r.y, r.radius * (0.35f + 0.65f * easeOut(k)), ringPaint)
        }

        for (ch in chunks) {
            if (now < ch.start) continue
            val t = now - ch.start
            val k = t / ch.life
            val bmp = assets.targets[ch.sprite]
            val s = spec.targets[ch.sprite]
            c.save()
            c.translate(ch.x + ch.vx * t, ch.y + ch.vy * t + 0.5f * GRAVITY * t * t)
            c.rotate(ch.rot + ch.spin * t)
            val sc = 1f - 0.35f * k
            c.scale(sc, sc)
            val big = max(bmp.width, bmp.height).toFloat()
            wedge.reset()
            wedge.moveTo(0f, 0f)
            var a = ch.a0
            while (a < ch.a1) {
                wedge.lineTo(cos(a) * big, sin(a) * big)
                a += 0.3f
            }
            wedge.lineTo(cos(ch.a1) * big, sin(ch.a1) * big)
            wedge.close()
            c.clipPath(wedge)
            plain.alpha = (255 * min(1f, (1f - k) / 0.35f)).toInt()
            c.drawBitmap(bmp, s.box.l - s.cx, s.box.t - s.cy, plain)
            c.restore()
        }
        plain.alpha = 255

        for (l in loot) {
            if (now < l.start) continue
            val k = ((now - l.start) / l.life).coerceIn(0f, 1f)
            // Pop out to the burst point, then swoop into the score panel.
            val (x, y) = if (k < 0.3f) {
                val q = easeOut(k / 0.3f)
                (l.x + (l.burstX - l.x) * q) to (l.y + (l.burstY - l.y) * q)
            } else {
                val q = ((k - 0.3f) / 0.7f).let { it * it }
                (l.burstX + (scoreX - l.burstX) * q) to (l.burstY + (scoreY - l.burstY) * q - sin(q * PI.toFloat()) * 80f)
            }
            val bmp = assets.targets[l.sprite]
            val sc = l.scale * (1f - 0.55f * k)
            c.save()
            c.translate(x, y)
            c.scale(sc, sc)
            c.rotate(360f * k)
            plain.alpha = (255 * min(1f, (1f - k) / 0.15f)).toInt()
            c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, plain)
            c.restore()
        }
        plain.alpha = 255

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

        for (p in popups) {
            if (now < p.start) continue
            val k = (now - p.start) / POPUP_LIFE
            val a = (255 * min(1f, (1f - k) / 0.3f)).toInt()
            val y = p.y - 70f * easeOut(k)
            val size = (if (p.time) 46f else 40f) * (1f + 0.25f * (1f - easeOut(min(1f, k * 4f))))
            c.saveLayerAlpha(p.x - 200f, y - 80f, p.x + 200f, y + 30f, a)
            if (p.time) hudText.white(c, p.text, p.x, y, size, 0xFF8CFF6A.toInt(), Paint.Align.CENTER)
            else hudText.gold(c, p.text, p.x, y, size, Paint.Align.CENTER, 0f, rim = false)
            c.restore()
        }
    }

    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k)
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    companion object {
        private const val GRAVITY = 1100f
        private const val FLASH_LIFE = 0.35f
        private const val POPUP_LIFE = 0.8f
    }
}
