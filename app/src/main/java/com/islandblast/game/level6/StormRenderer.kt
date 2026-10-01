package com.islandblast.game.level6

import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.islandblast.game.GameText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws Storm Dodge in stage units (941x1672, the approved design's pixel grid).
 * Order: river, sky plate (fades into the river), objects far to near, foreground
 * leaves, jet ski + spray, effects, rain, lightning, HUD, banners, overlays.
 */
class StormRenderer(private val assets: StormAssets) {
    /** Draw the pause and end cards here; off when a level host draws its own menus. */
    var overlays = true

    private val spec = assets.spec
    private val text = GameText(assets.fredoka)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val add = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val skiPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dst = RectF()
    private val path = Path()

    private val waterPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(assets.water, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val waterPaint2 = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(assets.water, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = 90
    }
    private val planeMatrix = Matrix()
    private val shaderMatrix = Matrix()

    // ---- transient effects -------------------------------------------------------
    private class Particle(var x: Float, var y: Float, val vx: Float, val vy: Float, val r: Float,
                           val color: Int, val start: Float, val life: Float, val gravity: Float = 0f)
    private class Popup(val text: String, val x: Float, val y: Float, val start: Float, val style: Int)

    /** Big callouts (BLOCKED!, SHIELD!, -150) stack instead of overlapping. */
    private fun callout(text: String, x: Float, y: Float, now: Float, style: Int) {
        val active = popups.count { it.style > 0 && now - it.start < 0.7f }
        popups += Popup(text, x, y - active * 78f, now, style)
    }
    private class Burst(val kind: Kind, val x: Float, val y: Float, val w: Float, val start: Float, val shielded: Boolean)
    private class FlyCoin(val x: Float, val y: Float, val start: Float)
    private class Bolt(val points: FloatArray, val start: Float)

    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private val bursts = ArrayList<Burst>()
    private val flyCoins = ArrayList<FlyCoin>()
    private val bolts = ArrayList<Bolt>()
    private var flashAt = -10f
    private var hitAt = -10f
    private var shieldBreakAt = -10f
    private var bannerSection = -1
    private var bannerAt = -10f
    private var bannerBonus = 0f
    private var shownScore = -1f
    private var lastTime = 0f
    private var rnd = Random(66)
    private var sprayCarry = 0f
    private var lastSkiX = 0f
    private var cam = 0f

    /** Pressed state of the arrow buttons (set by the view). */
    var leftPressed = false
    var rightPressed = false

    fun reset() {
        particles.clear(); popups.clear(); bursts.clear(); flyCoins.clear(); bolts.clear()
        flashAt = -10f; hitAt = -10f; shieldBreakAt = -10f; bannerSection = -1; bannerAt = -10f
        shownScore = -1f; lastTime = 0f; rnd = Random(66)
    }

    /** Turns game events into effects; call once per frame after update. */
    fun consume(game: StormGame) {
        val now = game.time
        for (e in game.events) when (e) {
            is StormEvent.Hit -> {
                val dz = e.z - game.z
                val x = spec.xAt(e.x, max(dz, 0f), camLanes(game))
                val y = spec.yAt(max(dz, 0f))
                val w = objectWidth(e.obj.kind, max(dz, 0f))
                bursts += Burst(e.obj.kind, x, y, w, now, e.shielded)
                if (e.shielded) {
                    shieldBreakAt = now
                    callout("BLOCKED!", skiX(game), spec.playerY - 330f, now, 2)
                    repeat(26) { spark(skiX(game), spec.playerY - 140f, 0xFF7FD8FF.toInt(), now, 260f, 820f, 7f) }
                } else {
                    hitAt = now
                    callout("-${game.rules.hitPenalty}", skiX(game), spec.playerY - 320f, now, 1)
                    val wood = e.obj.kind != Kind.MINE && e.obj.kind != Kind.X_HAZARD
                    repeat(if (wood) 22 else 30) {
                        val col = if (wood) (if (it % 3 == 0) 0xFFE0A060.toInt() else 0xFF7A4520.toInt())
                        else if (it % 2 == 0) 0xFFFFD040.toInt() else 0xFFFF5020.toInt()
                        spark(x, y - w * 0.3f, col, now, 300f, 1000f, if (wood) 9f else 8f, gravity = 1600f)
                    }
                    repeat(18) { spark(x, y, 0xFFFFFFFF.toInt(), now, 200f, 700f, 6f, gravity = 900f) }
                }
            }
            is StormEvent.Coin -> {
                val dz = max(e.obj.z - game.z, 0f)
                val x = spec.xAt(e.obj.x, dz, camLanes(game))
                val y = spec.yAt(dz) - objectWidth(Kind.COIN, dz) * 0.6f
                flyCoins += FlyCoin(x, y, now)
                popups += Popup("+${game.rules.coinPoints}", x, y - 40f, now, 0)
                repeat(8) { spark(x, y, 0xFFFFE070.toInt(), now, 150f, 450f, 5f) }
            }
            is StormEvent.Shield -> {
                callout("SHIELD!", skiX(game), spec.playerY - 330f, now, 2)
                repeat(24) { spark(skiX(game), spec.playerY - 160f, 0xFF80E0FF.toInt(), now, 200f, 640f, 7f) }
            }
            is StormEvent.SectionStart -> {
                bannerSection = e.index; bannerAt = now; bannerBonus = e.bonusSeconds
            }
            is StormEvent.Lightning -> strike(now)
            is StormEvent.Finished -> repeat(70) {
                spark(spec.stageW * (0.15f + 0.7f * rnd.nextFloat()), spec.stageH * 0.42f,
                    listOf(0xFFFFD040, 0xFF40D0FF, 0xFFFF60C0, 0xFF80FF60).map { c -> c.toInt() }[it % 4], now, 300f, 1100f, 8f, gravity = 900f)
            }
            else -> Unit
        }
        game.events.clear()
        // Spray from the jet ski's nozzle and hull while moving.
        val dt = (now - lastTime).coerceIn(0f, 0.1f)
        lastTime = now
        if (!game.over && game.speed > 0f) {
            sprayCarry += dt * (40f + game.speed * 3f)
            val sx = skiX(game)
            val steering = abs(sx - lastSkiX) / max(dt, 1e-3f)
            while (sprayCarry >= 1f) {
                sprayCarry -= 1f
                val side = if (rnd.nextBoolean()) 1f else -1f
                particles += Particle(sx + rnd.nextFloat() * 60f - 30f, spec.playerY + 30f, (rnd.nextFloat() - 0.5f) * 260f,
                    260f + rnd.nextFloat() * 260f, 6f + rnd.nextFloat() * 9f, 0xEEFFFFFF.toInt(), now, 0.45f, 300f)
                if (steering > 60f || rnd.nextFloat() < 0.35f) {
                    particles += Particle(sx + side * 160f, spec.playerY - 20f, side * (200f + rnd.nextFloat() * 300f),
                        -120f - rnd.nextFloat() * 200f, 5f + rnd.nextFloat() * 8f, 0xDDE8FBFF.toInt(), now, 0.5f, 1400f)
                }
            }
            lastSkiX = sx
        }
        val t = now
        particles.removeAll { t - it.start > it.life }
        popups.removeAll { t - it.start > 1.0f }
        bursts.removeAll { t - it.start > 0.6f }
        flyCoins.removeAll { t - it.start > 0.55f }
        bolts.removeAll { t - it.start > 0.5f }
    }

    private fun spark(x: Float, y: Float, color: Int, t0: Float, vMin: Float, vMax: Float, size: Float, gravity: Float = 0f) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val sp = vMin + rnd.nextFloat() * (vMax - vMin)
        particles += Particle(x, y, cos(a) * sp, sin(a) * sp, size * (0.6f + rnd.nextFloat() * 0.8f), color, t0,
            0.4f + rnd.nextFloat() * 0.35f, gravity)
    }

    private fun strike(now: Float) {
        flashAt = now
        // A jagged bolt from the clouds down to the ruins, away from the title sign.
        val sideL = rnd.nextBoolean()
        var x = if (sideL) 60f + rnd.nextFloat() * 220f else 650f + rnd.nextFloat() * 240f
        var y = -40f
        val pts = ArrayList<Float>()
        pts += x; pts += y
        val endY = 440f + rnd.nextFloat() * 110f
        while (y < endY) {
            y += 34f + rnd.nextFloat() * 38f
            x += (rnd.nextFloat() - 0.5f) * 70f
            pts += x; pts += y
        }
        bolts += Bolt(pts.toFloatArray(), now)
    }

    // ---- geometry ---------------------------------------------------------------------

    /** Objects shrink with distance a little slower than the river narrows (cartoon depth). */
    private fun sizeScale(dz: Float) = spec.scaleAt(dz).pow(0.6f)

    private fun objectWidth(k: Kind, dz: Float): Float {
        val lanes = when (k) {
            Kind.LOGS -> 1.45f
            Kind.COIN -> 0.5f
            Kind.SHIELD -> 0.62f
            else -> 2f * k.halfWidth * 1.5f
        }
        return lanes * spec.laneW * sizeScale(dz)
    }

    private val skiBox get() = spec.sprites.getValue("jetski")

    /**
     * Follow camera: trails the jet ski sideways ([CAM_FOLLOW] of its offset), so the
     * river slides under it with parallax, and banks a little into turns.
     */
    fun camLanes(game: StormGame) = game.x * CAM_FOLLOW
    private fun camRoll(game: StormGame) = ((game.targetX - game.x) * 2.2f).coerceIn(-1.8f, 1.8f)
    fun skiX(game: StormGame) = spec.xAt(game.x, 0f, camLanes(game))

    /** The jet ski's drawn rectangle (stage units), at its water contact on the player row. */
    fun skiRect(game: StormGame): RectF {
        val b = skiBox
        val k = spec.skiScale
        val cx = skiX(game)
        val bottom = spec.playerY + (b.b - spec.skiAnchorY) * k
        return RectF(cx - b.w / 2f * k, bottom - b.h * k, cx + b.w / 2f * k, bottom)
    }

    // ---- frame -------------------------------------------------------------------------

    fun draw(c: Canvas, game: StormGame) {
        val now = game.time
        val shake = shakeOffset(now)
        cam = camLanes(game)
        c.save()
        c.translate(shake, shake * 0.6f)
        c.rotate(camRoll(game), spec.vanishX, spec.playerY)
        drawRiver(c, game)
        drawSky(c, game)
        drawBanks(c, game, behindSki = false)
        drawObjects(c, game, now, behindSki = false)
        drawFinishGate(c, game)
        drawBanks(c, game, behindSki = true)
        drawLeaves(c)
        drawObjects(c, game, now, behindSki = true)
        drawSki(c, game, now)
        drawBursts(c, now)
        drawParticles(c, now)
        c.restore()
        drawRain(c, game, now)
        drawLightning(c, game, now)
        drawHitTint(c, now)
        drawHud(c, game, now)
        drawPopups(c, now)
        drawBanner(c, game, now)
        if (!overlays) return
        when {
            game.over -> drawEndCard(c, game, now)
            game.paused -> drawPaused(c)
        }
    }

    private fun shakeOffset(now: Float): Float {
        val k = now - hitAt
        return if (k in 0f..0.35f) sin(k * 90f) * 14f * (1f - k / 0.35f) else 0f
    }

    // ---- river --------------------------------------------------------------------------

    private fun drawRiver(c: Canvas, game: StormGame) {
        val near = -6f
        val far = 95f
        val u = 30f * spec.laneW
        val vk = 90f
        val sn = spec.scaleAt(near)
        val sf = spec.scaleAt(far)
        val src = floatArrayOf(-u, -near * vk, u, -near * vk, u, -far * vk, -u, -far * vk)
        val yN = spec.yAt(near)
        val yF = spec.yAt(far)
        val camPx = cam * spec.laneW
        val dstPts = floatArrayOf(
            spec.vanishX + (-u - camPx) * sn, yN, spec.vanishX + (u - camPx) * sn, yN,
            spec.vanishX + (u - camPx) * sf, yF, spec.vanishX + (-u - camPx) * sf, yF,
        )
        planeMatrix.setPolyToPoly(src, 0, dstPts, 0, 4)
        val tile = 0.9f * spec.laneW * 1.7f
        val k = tile / assets.water.width
        val scroll = (game.z * vk) % (assets.water.height * k)
        shaderMatrix.setScale(k, k)
        shaderMatrix.postTranslate(0f, scroll)
        waterPaint.shader.setLocalMatrix(shaderMatrix)
        // A second, larger layer drifting sideways: the current's turbulence.
        val m2 = Matrix()
        m2.setScale(k * 1.7f, k * 1.7f)
        m2.postTranslate(sin(game.time * 0.6f) * 60f + game.time * 25f, scroll * 0.8f + 140f)
        waterPaint2.shader.setLocalMatrix(m2)
        c.save()
        c.concat(planeMatrix)
        c.drawRect(-u, -far * vk, u, -near * vk, waterPaint)
        c.drawRect(-u, -far * vk, u, -near * vk, waterPaint2)
        c.restore()
        // Storm light: darker, purple-tinted toward the horizon.
        fill.shader = LinearGradient(0f, spec.horizon + 90f, 0f, spec.horizon + 520f,
            intArrayOf(0x883A2A80.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(-spec.skyMarginX, spec.horizon, spec.stageW + spec.skyMarginX, spec.stageH + 200f, fill)
        fill.shader = null
        drawWaves(c, game)
    }

    /** Big rolling waves: foam crests sweeping toward the jet ski, more in heavier sections. */
    private fun drawWaves(c: Canvas, game: StormGame) {
        val spacing = when (game.section) { 0 -> 34f; 1 -> 26f; 2 -> 18f; 3 -> 15f; else -> 40f }
        val strength = when (game.section) { 0 -> 0.45f; 1 -> 0.6f; 2 -> 0.85f; 3 -> 0.9f; else -> 0.35f }
        var wz = spacing - (game.z % spacing)
        while (wz < VISIBLE_FAR) {
            if (wz > 1.5f) {
                val s = spec.scaleAt(wz)
                val y = spec.yAt(wz)
                val a = (strength * 140f * min(1f, (VISIBLE_FAR - wz) / 14f)).roundToInt()
                stroke.color = Color.WHITE
                stroke.alpha = a
                stroke.strokeWidth = 10f * s + 2f
                path.reset()
                val half = 2.6f * spec.laneW * s
                val mid = spec.xAt(0f, wz, cam)
                var x = mid - half
                path.moveTo(x, y)
                val seg = 26
                for (i in 1..seg) {
                    x = mid - half + 2 * half * i / seg
                    path.lineTo(x, y - (sin(i * 1.3f + wz * 0.7f + game.time * 2f) * 7f + 5f) * s)
                }
                c.drawPath(path, stroke)
            }
            wz += spacing
        }
    }

    private fun drawSky(c: Canvas, game: StormGame) {
        c.drawBitmap(assets.sky, -spec.skyMarginX - cam * spec.laneW * 0.06f, -spec.skyMarginY, bmp)
        // The storm calms as the jet ski reaches the temple.
        val calm = calmness(game)
        if (calm > 0f) {
            fill.shader = LinearGradient(0f, 0f, 0f, spec.horizon + 200f,
                intArrayOf(withAlpha(0xFFFFC870.toInt(), (80 * calm).toInt()), 0x00FFC870), null, Shader.TileMode.CLAMP)
            c.drawRect(-spec.skyMarginX, -spec.skyMarginY, spec.stageW + spec.skyMarginX, spec.horizon + 200f, fill)
            fill.shader = null
        }
    }

    private fun calmness(game: StormGame): Float {
        val s5 = StormCourse.sections.last()
        return ((game.z - s5.start) / (s5.end - s5.start)).coerceIn(0f, 1f)
    }

    // ---- objects ------------------------------------------------------------------------

    private fun drawObjects(c: Canvas, game: StormGame, now: Float, behindSki: Boolean) {
        val visible = game.objects.filter {
            val dz = it.z - game.z
            !game.isTaken(it) && dz > -2.4f && dz < VISIBLE_FAR && (dz < 0.3f) == behindSki
        }
        for (o in visible.sortedByDescending { it.z }) drawObject(c, o, o.z - game.z, now)
    }

    private fun drawObject(c: Canvas, o: Placed, dz: Float, now: Float) {
        val s = spec.scaleAt(max(dz, -2.4f))
        val w = objectWidth(o.kind, max(dz, -2.4f))
        val x = spec.vanishX + (o.x - cam) * spec.laneW * s
        val y = spec.yAt(dz) + sin(now * 3f + o.z * 1.7f) * 5f * s
        val alpha = (255 * ((VISIBLE_FAR - dz) / 12f).coerceIn(0f, 1f)).toInt()
        when (o.kind) {
            Kind.COIN -> {
                // Spinning coin hovering above the water.
                val spin = cos(now * 4f + o.z)
                val b = if (abs(spin) < 0.35f) assets.sprite.getValue("coin_side") else assets.sprite.getValue("coin")
                val cw = w * max(0.18f, abs(spin))
                val h = w
                val cy = y - h * 0.75f
                glow(c, x, cy, w * 0.7f, 0xFFFFE070.toInt(), (alpha * 0.45f).toInt())
                fade.alpha = alpha
                dst.set(x - cw / 2f, cy - h / 2f, x + cw / 2f, cy + h / 2f)
                c.drawBitmap(b, null, dst, fade)
            }
            Kind.SHIELD -> {
                val pulse = 1f + 0.08f * sin(now * 5f)
                val ww = w * pulse
                val cy = y - ww * 0.7f
                glow(c, x, cy, ww * 0.85f, 0xFF60D0FF.toInt(), (alpha * 0.6f).toInt())
                fade.alpha = alpha
                dst.set(x - ww / 2f, cy - ww / 2f, x + ww / 2f, cy + ww / 2f)
                c.drawBitmap(assets.sprite.getValue("shield"), null, dst, fade)
            }
            Kind.X_HAZARD -> {
                val pulse = 1f + 0.07f * sin(now * 7f + o.z)
                val ww = w * pulse
                val cy = y - ww * 0.42f
                glow(c, x, cy, ww * 0.85f, 0xFFFF3050.toInt(), (alpha * (0.55f + 0.25f * sin(now * 7f))).toInt())
                fade.alpha = alpha
                dst.set(x - ww / 2f, cy - ww / 2f, x + ww / 2f, cy + ww / 2f)
                c.drawBitmap(assets.sprite.getValue("x_hazard"), null, dst, fade)
            }
            else -> {
                val b = assets.sprite.getValue(o.kind.sprite)
                val h = w * b.height / b.width
                // Sits in the water: the bottom sinks a little below the waterline, with a foam ring.
                foam(c, x, y, w, s, alpha, now + o.z)
                fade.alpha = alpha
                c.save()
                c.rotate(sin(now * 2.2f + o.z) * 4f, x, y)
                dst.set(x - w / 2f, y - h * 0.86f, x + w / 2f, y + h * 0.14f)
                c.drawBitmap(b, null, dst, fade)
                c.restore()
            }
        }
    }

    private fun foam(c: Canvas, x: Float, y: Float, w: Float, s: Float, alpha: Int, t: Float) {
        fill.shader = null
        fill.color = Color.WHITE
        fill.alpha = (alpha * 0.55f).toInt()
        dst.set(x - w * 0.62f, y - 14f * s, x + w * 0.62f, y + 18f * s)
        c.drawOval(dst, fill)
        fill.alpha = (alpha * 0.8f).toInt()
        for (i in 0 until 6) {
            val a = i / 6f * 2f * PI.toFloat() + t
            c.drawCircle(x + cos(a) * w * 0.55f, y + sin(a) * 9f * s, (5f + 3f * sin(t * 3 + i)) * s + 2f, fill)
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        if (alpha <= 0 || r <= 1f) return
        fill.shader = RadialGradient(x, y, r, intArrayOf(withAlpha(color, alpha.coerceIn(0, 255)), withAlpha(color, 0)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

    // ---- finish gate ----------------------------------------------------------------

    /** Wooden temple gate with torches across the river at the finish line. */
    private fun drawFinishGate(c: Canvas, game: StormGame) {
        val dz = StormCourse.length - game.z
        if (dz > VISIBLE_FAR + 20f || dz < -2f) return
        val s = spec.scaleAt(dz)
        val ss = sizeScale(dz)
        val y = spec.yAt(dz)
        val half = 1.75f * spec.laneW * s
        val postW = 46f * ss
        val postH = 520f * ss
        val alpha = (255 * ((VISIBLE_FAR + 20f - dz) / 12f).coerceIn(0f, 1f)).toInt()
        val gx = spec.xAt(0f, dz, cam)
        for (side in floatArrayOf(-1f, 1f)) {
            val px = gx + side * half
            fill.shader = LinearGradient(px - postW, 0f, px + postW, 0f, intArrayOf(0xFF5A3216.toInt(), 0xFFA8693A.toInt(), 0xFF5A3216.toInt()),
                null, Shader.TileMode.CLAMP)
            fill.alpha = alpha
            dst.set(px - postW / 2f, y - postH, px + postW / 2f, y)
            c.drawRoundRect(dst, 8f * ss, 8f * ss, fill)
            fill.shader = null
            // torch
            glow(c, px, y - postH - 18f * ss, 70f * ss, 0xFFFFA020.toInt(), alpha)
            fill.color = 0xFFFFD040.toInt(); fill.alpha = alpha
            c.drawCircle(px, y - postH - 14f * ss, 16f * ss, fill)
            fill.color = 0xFFFFFFE0.toInt(); fill.alpha = alpha
            c.drawCircle(px, y - postH - 10f * ss, 8f * ss, fill)
        }
        // banner plank
        val bw = half * 2f + postW
        val bh = 92f * ss
        val by = y - postH * 0.86f
        fill.shader = LinearGradient(0f, by, 0f, by + bh, intArrayOf(0xFFC07A3C.toInt(), 0xFF7A4520.toInt()), null, Shader.TileMode.CLAMP)
        fill.alpha = alpha
        dst.set(gx - bw / 2f, by, gx + bw / 2f, by + bh)
        c.drawRoundRect(dst, 12f * ss, 12f * ss, fill)
        fill.shader = null
        stroke.color = 0xFF3A1C08.toInt(); stroke.alpha = alpha; stroke.strokeWidth = 5f * ss
        c.drawRoundRect(dst, 12f * ss, 12f * ss, stroke)
        text.gold(c, "FINISH", gx, by + bh * 0.76f, 70f * ss, alpha = alpha)
    }

    /**
     * Jungle foliage along both banks, passing the jet ski: the strongest cue of forward
     * speed. Fixed positions along the river (every [BANK_STEP] units), drawn with the
     * painted leaves, mirrored on the right bank.
     */
    private fun drawBanks(c: Canvas, game: StormGame, behindSki: Boolean) {
        val leaves = assets.sprite.getValue("leaves")
        val first = kotlin.math.floor((game.z - 6f) / BANK_STEP).toInt()
        val last = kotlin.math.floor((game.z + VISIBLE_FAR) / BANK_STEP).toInt()
        for (i in last downTo first) {
            val wz = i * BANK_STEP
            val dz = wz - game.z
            if (dz <= -6f || dz >= VISIBLE_FAR || (dz < 0.3f) != behindSki) continue
            val side = if (i % 2 == 0) -1f else 1f
            val s = spec.scaleAt(dz)
            val w = 1.5f * spec.laneW * sizeScale(dz)
            val h = w * leaves.height / leaves.width
            val x = spec.vanishX + (side * (2.95f + (i * 37 % 5) * 0.08f) - cam) * spec.laneW * s
            val y = spec.yAt(dz) + 10f * s
            fade.alpha = (255 * ((VISIBLE_FAR - dz) / 12f).coerceIn(0f, 1f)).toInt()
            c.save()
            if (side > 0) c.scale(-1f, 1f, x, y)
            dst.set(x - w * 0.7f, y - h, x + w * 0.3f, y)
            c.drawBitmap(leaves, null, dst, fade)
            c.restore()
        }
    }

    private fun drawLeaves(c: Canvas) {
        val b = spec.sprites.getValue("leaves")
        c.drawBitmap(assets.sprite.getValue("leaves"), b.l, b.t, bmp)
    }

    // ---- jet ski ---------------------------------------------------------------------

    private fun drawSki(c: Canvas, game: StormGame, now: Float) {
        val r = skiRect(game)
        val tilt = ((game.targetX - game.x) * 9f).coerceIn(-10f, 10f)
        val bob = sin(now * 6f) * 4f + if (game.section >= 2) sin(now * 11f) * 3f else 0f
        // Just hit: the jet ski flickers (never disappears) while it can't be hit again.
        val flicker = game.invulnerable && !game.shielded && ((now * 14f).toInt() % 2 == 0)
        skiPaint.alpha = if (flicker) 110 else 255
        val contactY = spec.playerY
        c.save()
        c.translate(0f, bob)
        c.rotate(tilt, r.centerX(), contactY)
        // wake under the ski
        fill.shader = RadialGradient(r.centerX(), contactY + 10f, r.width() * 0.62f,
            intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        dst.set(r.centerX() - r.width() * 0.65f, contactY - r.height() * 0.2f, r.centerX() + r.width() * 0.65f, contactY + r.height() * 0.22f)
        c.drawOval(dst, fill)
        fill.shader = null
        c.drawBitmap(assets.sprite.getValue("jetski"), null, r, skiPaint)
        c.restore()
        val cx = r.centerX()
        val cy = r.top + r.height() * 0.55f
        if (game.shielded) {
            val left = game.shieldUntil - now
            val blink = left > 2f || ((now * 8f).toInt() % 2 == 0)
            if (blink) {
                val rr = r.width() * 0.62f + 6f * sin(now * 6f)
                fill.shader = RadialGradient(cx, cy, rr, intArrayOf(0x0040C0FF, 0x3340C0FF, 0x9960D8FF.toInt()),
                    floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
                dst.set(cx - rr, cy - rr * 1.05f, cx + rr, cy + rr * 1.05f)
                c.drawOval(dst, fill)
                fill.shader = null
                stroke.color = 0xFFBFF0FF.toInt(); stroke.alpha = 200; stroke.strokeWidth = 5f
                c.drawOval(dst, stroke)
            }
        }
        val kb = now - shieldBreakAt
        if (kb in 0f..0.45f) {
            val k = kb / 0.45f
            stroke.color = 0xFF9BE6FF.toInt(); stroke.alpha = (255 * (1 - k)).toInt(); stroke.strokeWidth = 14f * (1 - k) + 2f
            c.drawCircle(cx, cy, r.width() * (0.6f + 0.7f * k), stroke)
        }
    }

    private fun drawBursts(c: Canvas, now: Float) {
        for (b in bursts) {
            val k = ((now - b.start) / 0.6f).coerceIn(0f, 1f)
            if (b.kind == Kind.MINE || b.kind == Kind.X_HAZARD) {
                glow(c, b.x, b.y - b.w * 0.3f, b.w * (0.8f + 1.4f * k), 0xFFFFB030.toInt(), (255 * (1 - k)).toInt())
                glow(c, b.x, b.y - b.w * 0.3f, b.w * (0.4f + 0.6f * k), 0xFFFFFFE0.toInt(), (255 * (1 - k)).toInt())
            } else {
                val bm = assets.sprite.getValue(b.kind.sprite)
                val h = b.w * bm.height / bm.width
                fade.alpha = (255 * (1 - k)).toInt()
                c.save()
                c.translate(b.x + (if (b.shielded) 260f else 0f) * k * (if (b.x < spec.vanishX) -1f else 1f), b.y - 200f * k)
                c.rotate(220f * k)
                c.scale(1f + 0.5f * k, 1f + 0.5f * k)
                dst.set(-b.w / 2f, -h * 0.86f, b.w / 2f, h * 0.14f)
                c.drawBitmap(bm, null, dst, fade)
                c.restore()
            }
        }
        // Collected coins fly to the score panel.
        for (f in flyCoins) {
            val k = ((now - f.start) / 0.55f).coerceIn(0f, 1f)
            val e = k * k
            val tx = spec.scoreX + 70f
            val ty = spec.scoreY - 30f
            val x = f.x + (tx - f.x) * e
            val y = f.y + (ty - f.y) * e - sin(k * PI.toFloat()) * 120f
            val r = 34f * (1f - 0.6f * k)
            dst.set(x - r, y - r, x + r, y + r)
            c.drawBitmap(assets.sprite.getValue("coin"), null, dst, bmp)
        }
    }

    private fun drawParticles(c: Canvas, now: Float) {
        for (p in particles) {
            val t = now - p.start
            if (t < 0f || t > p.life) continue
            val k = t / p.life
            val x = p.x + p.vx * t
            val y = p.y + p.vy * t + 0.5f * p.gravity * t * t
            fill.shader = null
            fill.color = p.color
            fill.alpha = (Color.alpha(p.color) * (1f - k)).toInt()
            c.drawCircle(x, y, p.r * (1f - 0.4f * k), fill)
        }
    }

    // ---- storm --------------------------------------------------------------------------

    private fun drawRain(c: Canvas, game: StormGame, now: Float) {
        val calm = calmness(game)
        val density = when (game.section) { 0 -> 70; 1 -> 95; 2 -> 140; 3 -> 150; else -> 80 }
        val n = (density * (1f - 0.85f * calm)).toInt()
        stroke.strokeWidth = 3.2f
        val h = spec.stageH + 300f
        for (i in 0 until n) {
            val seed = (i * 7919 + 13) % 10007
            val x0 = (seed * 0.0941f * 1000f) % (spec.stageW + 400f) - 200f
            val speed = 1500f + (seed % 500)
            val len = 34f + (seed % 30)
            val y = ((now * speed + seed * 37f) % h) - 150f
            val x = x0 - y * 0.22f
            stroke.color = Color.WHITE
            stroke.alpha = 85 + seed % 70
            c.drawLine(x, y, x - len * 0.22f, y + len, stroke)
        }
    }

    private fun drawLightning(c: Canvas, game: StormGame, now: Float) {
        for (b in bolts) {
            val k = (now - b.start) / 0.5f
            if (k < 0f || k > 1f) continue
            val flicker = if (k < 0.15f || k in 0.25f..0.4f) 1f else 0.35f
            val a = (255 * (1f - k) * flicker).toInt()
            path.reset()
            path.moveTo(b.points[0], b.points[1])
            var i = 2
            while (i < b.points.size) { path.lineTo(b.points[i], b.points[i + 1]); i += 2 }
            stroke.color = 0xFFB070FF.toInt(); stroke.alpha = a / 2; stroke.strokeWidth = 26f
            stroke.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path, stroke)
            stroke.maskFilter = null
            stroke.color = 0xFFE8D8FF.toInt(); stroke.alpha = a; stroke.strokeWidth = 7f
            c.drawPath(path, stroke)
            stroke.color = Color.WHITE; stroke.alpha = a; stroke.strokeWidth = 3f
            c.drawPath(path, stroke)
        }
        val k = now - flashAt
        if (k in 0f..0.35f) {
            val flick = if (k < 0.06f) 1f else if (k < 0.12f) 0.3f else if (k < 0.18f) 0.8f else (0.35f - k) / 0.17f
            fill.shader = null
            fill.color = 0xFFE6DCFF.toInt()
            fill.alpha = (0.28f * 255 * flick).toInt()
            c.drawRect(-spec.skyMarginX, -spec.skyMarginY, spec.stageW + spec.skyMarginX, spec.stageH + 200f, fill)
        }
    }

    private fun drawHitTint(c: Canvas, now: Float) {
        val k = now - hitAt
        if (k !in 0f..0.45f) return
        val a = (150 * (1f - k / 0.45f)).toInt()
        fill.shader = RadialGradient(spec.stageW / 2f, spec.stageH / 2f, spec.stageH * 0.75f,
            intArrayOf(0x00FF2020, withAlpha(0xFFFF2020.toInt(), a)), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(-spec.skyMarginX, -spec.skyMarginY, spec.stageW + spec.skyMarginX, spec.stageH + 200f, fill)
        fill.shader = null
    }

    // ---- HUD --------------------------------------------------------------------------

    private fun drawHud(c: Canvas, game: StormGame, now: Float) {
        // Stars: the plate paints two gold and one empty; draw only what differs.
        val stars = game.liveStars()
        for (i in spec.stars.indices) {
            val want = i < stars
            if (want == (i < 2)) continue
            val b = spec.stars[i]
            val gold = assets.sprite.getValue("star_gold")
            val empty = assets.sprite.getValue("star_empty")
            dst.set(b.l, b.t, b.r, b.b)
            c.drawBitmap(empty, null, dst, bmp)
            if (want) c.drawBitmap(gold, null, dst, bmp)
        }
        // Score rolls up.
        if (shownScore < 0f) shownScore = game.score.toFloat()
        val target = game.score.toFloat()
        shownScore = if (abs(target - shownScore) < 1f) target else shownScore + (target - shownScore) * 0.25f
        text.cream(c, "%,d".format(shownScore.roundToInt()), spec.scoreX, spec.scoreY, spec.scoreSize)
        // Timer: green flash on checkpoint bonus, red pulse when low.
        val secs = game.timerSeconds
        val bonus = (now - game.lastCheckpointAt).let { if (it in 0f..0.8f) 1f - it / 0.8f else 0f }
        val col = when {
            bonus > 0f -> lerp(Color.WHITE, 0xFF8CFF6A.toInt(), bonus)
            secs <= 10 && !game.over -> lerp(Color.WHITE, 0xFFFF5A48.toInt(), 0.5f + 0.5f * sin(now * 10f))
            else -> Color.WHITE
        }
        text.white(c, "%d:%02d".format(secs / 60, secs % 60), spec.timerX, spec.timerY, spec.timerSize * (1f + 0.12f * bonus), col)
        // Arrow buttons.
        for ((name, pressed) in listOf("arrow_left" to leftPressed, "arrow_right" to rightPressed)) {
            val b = spec.sprites.getValue(name)
            val k = if (pressed) 0.9f else 1f
            c.save()
            c.scale(k, k, b.cx, b.cy)
            c.drawBitmap(assets.sprite.getValue(name), b.l, b.t, bmp)
            if (pressed) glow(c, b.cx, b.cy, b.w * 0.55f, 0xFFFFFFFF.toInt(), 70)
            c.restore()
        }
        // River progress: a slim bar under the stars, with a tick at each section checkpoint.
        val p = game.progress
        val bx0 = 318f; val bx1 = 572f; val by = 306f
        fill.shader = null
        fill.color = 0xAA0A0820.toInt()
        dst.set(bx0 - 4f, by - 7f, bx1 + 4f, by + 7f)
        c.drawRoundRect(dst, 7f, 7f, fill)
        fill.shader = LinearGradient(bx0, 0f, bx1, 0f, intArrayOf(0xFF40D8FF.toInt(), 0xFFFFD040.toInt()), null, Shader.TileMode.CLAMP)
        dst.set(bx0, by - 4f, bx0 + (bx1 - bx0) * p, by + 4f)
        c.drawRoundRect(dst, 4f, 4f, fill)
        fill.shader = null
        for (s in StormCourse.sections.drop(1)) {
            val x = bx0 + (bx1 - bx0) * s.start / StormCourse.length
            fill.color = 0xCCFFFFFF.toInt()
            c.drawCircle(x, by, 3.5f, fill)
        }
    }

    private fun drawPopups(c: Canvas, now: Float) {
        for (p in popups) {
            val k = ((now - p.start) / 1.0f).coerceIn(0f, 1f)
            val y = p.y - 90f * k
            val a = (255 * (1f - k * k)).toInt()
            val size = (if (p.style == 0) 40f else 64f) * (1f + 0.3f * (1f - min(1f, k * 5f)))
            when (p.style) {
                0 -> text.gold(c, p.text, p.x, y, size, rim = false, alpha = a)
                1 -> text.white(c, p.text, p.x, y, size, 0xFFFF4A3A.toInt(), android.graphics.Paint.Align.CENTER, alpha = a)
                else -> text.blue(c, p.text, p.x, y, size, alpha = a)
            }
        }
    }

    private fun drawBanner(c: Canvas, game: StormGame, now: Float) {
        val k = now - bannerAt
        if (bannerSection < 0 || k !in 0f..2.2f) return
        val inK = min(1f, k / 0.25f)
        val outK = ((2.2f - k) / 0.35f).coerceIn(0f, 1f)
        val a = (255 * min(inK, outK)).toInt()
        val y = 450f - 40f * (1f - inK)
        val s = StormCourse.sections[bannerSection]
        val title = s.name.uppercase()
        val w = text.measure(title, 66f) + 120f
        fill.shader = null
        fill.color = 0xCC140A28.toInt(); fill.alpha = (0xCC * a / 255)
        dst.set(spec.stageW / 2f - w / 2f, y - 92f, spec.stageW / 2f + w / 2f, y + 34f)
        c.drawRoundRect(dst, 26f, 26f, fill)
        stroke.color = 0xFF8E6BFF.toInt(); stroke.alpha = a; stroke.strokeWidth = 4f
        c.drawRoundRect(dst, 26f, 26f, stroke)
        text.white(c, if (bannerSection == 4) "FINAL STRETCH" else "SECTION ${bannerSection + 1}", spec.stageW / 2f, y - 50f, 34f,
            align = android.graphics.Paint.Align.CENTER, alpha = a)
        text.gold(c, title, spec.stageW / 2f, y + 12f, 58f, alpha = a)
        if (bannerBonus > 0f) {
            text.white(c, "+${bannerBonus.roundToInt()}s", spec.timerX + 50f, spec.timerY + 62f, 40f, 0xFF8CFF6A.toInt(),
                android.graphics.Paint.Align.CENTER, alpha = a)
        }
    }

    // ---- overlays ----------------------------------------------------------------------

    private fun drawPaused(c: Canvas) {
        c.drawColor(0x8C000000.toInt())
        text.gold(c, "Paused", spec.stageW / 2f, spec.stageH * 0.42f, 96f)
        text.white(c, "Tap to resume", spec.stageW / 2f, spec.stageH * 0.42f + 76f, 40f, align = android.graphics.Paint.Align.CENTER)
    }

    private fun drawEndCard(c: Canvas, game: StormGame, now: Float) {
        val since = now - (game.finishedAt.takeIf { it >= 0f } ?: now)
        val a = (min(1f, since / 0.5f) * 255).toInt()
        c.drawColor(withAlpha(0xFF0A0618.toInt(), (0.8f * a).toInt()))
        val cx = spec.stageW / 2f
        val top = spec.stageH * 0.26f
        val won = game.phase == StormPhase.FINISHED
        if (won) {
            text.gold(c, "STORM", cx, top, 110f, alpha = a)
            text.blue(c, "SURVIVED!", cx, top + 104f, 96f, alpha = a)
        } else {
            text.gold(c, "OUT OF", cx, top, 100f, alpha = a)
            text.gold(c, "TIME!", cx, top + 100f, 100f, alpha = a)
        }
        // Stars pop in one by one.
        for (i in 0 until 3) {
            val t0 = 0.5f + i * 0.35f
            val k = ((since - t0) / 0.3f).coerceIn(0f, 1f)
            val earned = i < game.stars
            val b = assets.sprite.getValue(if (earned && k > 0f) "star_gold" else "star_empty")
            val sz = 150f * (if (earned) 0.6f + 0.4f * overshoot(k) else 1f)
            val sx = cx + (i - 1) * 170f
            val sy = top + 250f - (if (i == 1) 30f else 0f)
            dst.set(sx - sz / 2f, sy - sz / 2f, sx + sz / 2f, sy + sz / 2f)
            fade.alpha = a
            c.drawBitmap(b, null, dst, fade)
        }
        val rows = listOf(
            "Coins" to "${game.coins} / ${game.totalCoins}",
            "Hits" to "${game.hits}" + if (game.shieldsUsed > 0) "  (+${game.shieldsUsed} blocked)" else "",
            "Time bonus" to (if (won) "+${game.timerSeconds * game.rules.timeBonusPerSecond}" else "—"),
            "No-hit bonus" to (if (won && game.hits == 0) "+${game.rules.noHitBonus}" else "—"),
        )
        var y = top + 420f
        fill.shader = null
        fill.color = 0xE6140A28.toInt(); fill.alpha = (0xE6 * a / 255)
        dst.set(cx - 350f, y - 70f, cx + 350f, y + rows.size * 64f + 80f)
        c.drawRoundRect(dst, 36f, 36f, fill)
        stroke.color = 0xFF8E6BFF.toInt(); stroke.alpha = a; stroke.strokeWidth = 4f
        c.drawRoundRect(dst, 36f, 36f, stroke)
        for ((label, value) in rows) {
            text.white(c, label, cx - 300f, y, 44f, alpha = a)
            text.white(c, value, cx + 300f, y, 44f, align = android.graphics.Paint.Align.RIGHT, alpha = a)
            y += 64f
        }
        text.white(c, "Score", cx - 300f, y + 40f, 54f, alpha = a)
        text.cream(c, "%,d".format(game.score), cx + 300f, y + 40f, 72f, android.graphics.Paint.Align.RIGHT)
        if (since > 1.6f) {
            val blink = 0.6f + 0.4f * sin(now * 4f)
            text.white(c, "Tap to ride again", cx, y + 170f, 44f, align = android.graphics.Paint.Align.CENTER, alpha = (a * blink).toInt())
        }
    }

    /** 0 before the pop, a little past 1 mid-pop, settling at 1. */
    private fun overshoot(k: Float): Float = if (k <= 0f) 0f else 1f + 0.25f * sin(k * PI.toFloat()) * (1f - k)

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    companion object {
        /** How far ahead (river units) objects are drawn: they emerge mid-river and approach. */
        const val VISIBLE_FAR = 58f
        /** The camera follows this share of the jet ski's sideways offset. */
        const val CAM_FOLLOW = 0.32f
        const val BANK_STEP = 7f
    }
}
