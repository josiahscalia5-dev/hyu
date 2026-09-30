package com.islandblast.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.islandblast.game.model.GameColor
import com.islandblast.game.model.GameEvent
import com.islandblast.game.model.Level5Game
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hit feedback in the style of the approved screen: the jagged star flash, gem shards
 * and sparks, plus rings for colour shifts and ball loads. Driven by game time so it
 * freezes with the pause and replays deterministically in tests.
 */
class Effects(private val assets: Assets, seed: Int = 5) {
    private val rnd = Random(seed)

    private class Flash(val x: Float, val y: Float, val size: Float, val color: GameColor, val start: Float)
    private class Shard(
        val bmp: Bitmap, var x: Float, var y: Float, val vx: Float, val vy: Float,
        val spin: Float, val scale: Float, val start: Float, val life: Float,
    )
    private class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Int, val size: Float,
                        val start: Float, val life: Float)
    private class Ring(val x: Float, val y: Float, val radius: Float, val color: Int, val start: Float,
                       val life: Float, val width: Float)

    private val flashes = ArrayList<Flash>()
    private val shards = ArrayList<Shard>()
    private val sparks = ArrayList<Spark>()
    private val rings = ArrayList<Ring>()

    private val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dst = RectF()

    fun consume(game: Level5Game) {
        val now = game.time
        for (e in game.events) when (e) {
            is GameEvent.Cleared -> blockBurst(game, e, now)
            is GameEvent.Bounced -> {
                repeat(6) { spark(e.x, e.y, 0xFFFFFFFF.toInt(), now, 180f, 420f, 3f) }
                rings += Ring(e.x, e.y, 34f, 0xFFFFFFFF.toInt(), now, 0.22f, 4f)
            }
            is GameEvent.Shifted -> for (i in e.blocks) {
                val b = game.board.blocks[i]
                if (!b.alive) continue
                val r = b.spec.rect
                rings += Ring(r.cx, r.cy, max(r.w, r.h) * 0.62f, b.color.swatch, now, 0.45f, 6f)
                repeat(5) { spark(r.cx, r.cy, b.color.glow, now, 120f, 320f, 3.5f) }
            }
            is GameEvent.Loaded -> {
                val s = game.spec
                rings += Ring(s.ballX, s.ballY, s.ballRadius * 1.35f, e.color.swatch, now, 0.3f, 7f)
            }
            is GameEvent.Won -> {
                val s = game.spec
                repeat(40) {
                    spark(s.stageW / 2f + (rnd.nextFloat() - 0.5f) * s.stageW * 0.45f, s.stageH * 0.4f,
                        GameColor.entries.random(rnd).glow, now, 300f, 900f, 5f)
                }
            }
            else -> Unit
        }
        game.events.clear()
        prune(now)
    }

    /** Drops finished effects. Runs every frame, drawn or not (e.g. while the screen is off). */
    private fun prune(now: Float) {
        rings.removeAll { now - it.start > it.life }
        flashes.removeAll { now - it.start > FLASH_LIFE }
        shards.removeAll { now - it.start > it.life }
        sparks.removeAll { now - it.start > it.life }
    }

    private fun blockBurst(game: Level5Game, e: GameEvent.Cleared, now: Float) {
        rings += Ring(e.x, e.y, 150f, e.color.glow, now, 0.32f, 10f)
        flashes += Flash(e.x, e.y, 1.45f, e.color, now)
        for (i in e.group) {
            val r = game.board.blocks[i].spec.rect
            val delay = hypot(r.cx - e.x, r.cy - e.y) / 2600f
            val t0 = now + delay
            flashes += Flash(r.cx, r.cy, max(r.w, r.h) / 95f, e.color, t0)
            val pool = assets.shards.getValue(e.color)
            repeat(4) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val sp = 260f + rnd.nextFloat() * 420f
                shards += Shard(
                    pool[rnd.nextInt(pool.size)],
                    r.cx + (rnd.nextFloat() - 0.5f) * r.w * 0.6f, r.cy + (rnd.nextFloat() - 0.5f) * r.h * 0.6f,
                    cos(a) * sp, sin(a) * sp - 180f, (rnd.nextFloat() - 0.5f) * 720f,
                    0.55f + rnd.nextFloat() * 0.45f, t0, 0.62f + rnd.nextFloat() * 0.3f,
                )
            }
            repeat(7) { spark(r.cx, r.cy, e.color.glow, t0, 260f, 820f, 4.5f) }
        }
    }

    private fun spark(x: Float, y: Float, color: Int, t0: Float, vMin: Float, vMax: Float, size: Float) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val sp = vMin + rnd.nextFloat() * (vMax - vMin)
        sparks += Spark(x, y, cos(a) * sp, sin(a) * sp, color, size * (0.6f + rnd.nextFloat() * 0.8f),
            t0, 0.32f + rnd.nextFloat() * 0.25f)
    }

    fun draw(c: Canvas, now: Float) {
        for (r in rings) {
            val k = ((now - r.start) / r.life).coerceIn(0f, 1f)
            if (now < r.start || now - r.start > r.life) continue
            ringPaint.color = r.color
            ringPaint.alpha = (255 * (1f - k)).toInt()
            ringPaint.strokeWidth = r.width * (1f - 0.6f * k)
            c.drawCircle(r.x, r.y, r.radius * (0.35f + 0.65f * easeOut(k)), ringPaint)
        }

        for (f in flashes) {
            if (now < f.start || now - f.start > FLASH_LIFE) continue
            val k = (now - f.start) / FLASH_LIFE
            val bmp = assets.burst.getValue(f.color)
            val s = f.size * (0.6f + 0.75f * easeOut(k))
            val hw = bmp.width / 2f * s
            val hh = bmp.height / 2f * s
            add.alpha = (255 * (1f - k * k)).toInt()
            dst.set(f.x - hw, f.y - hh, f.x + hw, f.y + hh)
            c.drawBitmap(bmp, null, dst, add)
            if (f.color != GameColor.YELLOW) {
                // White-hot centre in the reference's warm tone, whatever the block colour.
                val core = assets.burst.getValue(GameColor.YELLOW)
                val cs = s * 0.55f
                dst.set(f.x - core.width / 2f * cs, f.y - core.height / 2f * cs,
                    f.x + core.width / 2f * cs, f.y + core.height / 2f * cs)
                add.alpha = (230 * (1f - k)).toInt()
                c.drawBitmap(core, null, dst, add)
            }
        }

        for (s in shards) {
            if (now < s.start || now - s.start > s.life) continue
            val t = now - s.start
            val k = t / s.life
            val x = s.x + s.vx * t
            val y = s.y + s.vy * t + 0.5f * GRAVITY * t * t
            plain.alpha = (255 * min(1f, (1f - k) / 0.35f)).toInt()
            c.save()
            c.translate(x, y)
            c.rotate(s.spin * t)
            c.scale(s.scale, s.scale)
            c.drawBitmap(s.bmp, -s.bmp.width / 2f, -s.bmp.height / 2f, plain)
            c.restore()
        }
        plain.alpha = 255

        for (p in sparks) {
            if (now < p.start || now - p.start > p.life) continue
            val t = now - p.start
            val k = t / p.life
            val x = p.x + p.vx * t
            val y = p.y + p.vy * t + 0.5f * GRAVITY * 0.5f * t * t
            sparkPaint.color = p.color
            sparkPaint.alpha = (200 * (1f - k)).toInt()
            c.drawCircle(x, y, p.size * 1.8f * (1f - 0.5f * k), sparkPaint)
            sparkPaint.color = 0xFFFFFFFF.toInt()
            sparkPaint.alpha = (255 * (1f - k)).toInt()
            c.drawCircle(x, y, p.size * 0.7f * (1f - 0.5f * k), sparkPaint)
        }
    }

    val busy get() = flashes.isNotEmpty() || shards.isNotEmpty() || sparks.isNotEmpty() || rings.isNotEmpty()

    fun clear() {
        flashes.clear(); shards.clear(); sparks.clear(); rings.clear()
    }

    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k)

    companion object {
        private const val FLASH_LIFE = 0.38f
        private const val GRAVITY = 1500f
    }
}
