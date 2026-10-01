package com.islandblast.game.temple

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws Temple Chase in stage units (941x1672, the approved design's pixel grid).
 * Order: lava, stone path, the painted opening ground (fading out as the run starts), the
 * painted backdrop (temple, golem, top HUD), side scenery far to near, items far to near,
 * runner, effects, HUD, banners, overlays.
 */
class TempleRenderer(private val assets: TempleAssets) {
    private val spec = assets.spec
    private val text = GameText(assets.fredoka)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dst = RectF()
    private val path = Path()

    private val lavaPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(assets.lava, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val lavaFlow = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(assets.lava, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val pathPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(assets.path, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    }
    private val introPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(assets.introGround, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val planeMatrix = Matrix()
    private val shaderMatrix = Matrix()

    // ---- transient effects ----------------------------------------------------------------
    private class Particle(val x: Float, val y: Float, val vx: Float, val vy: Float, val r: Float,
                           val color: Int, val start: Float, val life: Float, val gravity: Float = 0f)
    private class Popup(val text: String, val x: Float, val y: Float, val start: Float, val style: Int)
    private class Flyer(val sprite: String, val x: Float, val y: Float, val size: Float, val start: Float, val tx: Float, val ty: Float)
    private class Debris(val sprite: String, val x: Float, val y: Float, val w: Float, val start: Float, val dir: Float)

    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private val flyers = ArrayList<Flyer>()
    private val debris = ArrayList<Debris>()
    private var hitAt = -10f
    private var blockAt = -10f
    private var goAt = -10f
    private var bannerSection = -1
    private var bannerAt = -10f
    private var bannerBonus = 0f
    private var shownScore = -1f
    private var lastTime = 0f
    private var emberCarry = 0f
    private var dustCarry = 0f
    private var heartLostAt = FloatArray(2) { -10f }
    private var starAt = FloatArray(3) { -10f }
    private var powerAt = FloatArray(3) { -10f }
    private var rnd = Random(55)
    private var cam = 0f

    /** Pressed state of the arrow buttons (set by the view). */
    var leftPressed = false
    var rightPressed = false

    fun reset() {
        particles.clear(); popups.clear(); flyers.clear(); debris.clear()
        hitAt = -10f; blockAt = -10f; goAt = -10f; bannerSection = -1; bannerAt = -10f
        shownScore = -1f; lastTime = 0f; emberCarry = 0f; dustCarry = 0f
        heartLostAt.fill(-10f); starAt.fill(-10f); powerAt.fill(-10f)
        rnd = Random(55)
    }

    // ---- geometry ---------------------------------------------------------------------------

    /** The camera trails the runner sideways (part of its offset); the painted camera sits a little right. */
    fun camLanes(game: TempleGame) = spec.camBias + game.x * CAM_FOLLOW
    fun runnerX(game: TempleGame) = spec.xAt(game.x, 0f, camLanes(game))

    /** Boulders roll toward the runner: drawn farther away than where they meet the runner. */
    private fun drawDz(o: Placed, dz: Float) = if (o.kind == Item.BOULDER && dz > 0f) dz * BOULDER_ROLL else dz

    /** Item sizes in lanes. Boulders shrink with distance a little slower than the path. */
    private fun itemWidth(o: Placed, dz: Float): Float {
        val s = spec.scaleAt(dz)
        return when (o.kind) {
            Item.BOULDER -> (if (o.decor) 1.75f else 1.45f) * spec.laneW * s.pow(0.6f)
            Item.BLOCK -> 1.0f * spec.laneW * s
            Item.COIN -> 0.84f * spec.laneW * s
            Item.GEM -> 0.85f * spec.laneW * s
            else -> 0.72f * spec.laneW * s
        }
    }

    /** Where an item is drawn now (centre x, ground y, width). */
    private fun itemScreen(o: Placed, game: TempleGame, out: FloatArray) {
        val dz = drawDz(o, o.z - game.dist)
        out[0] = spec.xAt(o.x, max(dz, -1.4f), cam)
        out[1] = spec.yAt(max(dz, -1.4f))
        out[2] = itemWidth(o, max(dz, -1.4f))
    }

    private val tmp = FloatArray(3)

    /** The runner's drawn rectangle (stage units): the painted runner, feet on the runner row. */
    fun runnerRect(game: TempleGame): RectF {
        val b = spec.sprites.getValue("runner")
        val cx = runnerX(game)
        val left = cx - (PAINTED_RUNNER_X - b.l)
        return RectF(left, b.t, left + b.w, b.b)
    }

    // ---- events -------------------------------------------------------------------------------

    /** Turns game events into effects; call once per frame after update. */
    fun consume(game: TempleGame) {
        val now = game.time
        cam = camLanes(game)
        val rx = runnerX(game)
        val ry = spec.playerY - 230f
        for (e in game.events) when (e) {
            is TempleEvent.Coin -> {
                itemScreen(e.obj, game, tmp)
                val y = tmp[1] - tmp[2] * 0.9f
                flyers += Flyer("coin", if (e.magnet) tmp[0] else rx, if (e.magnet) y else ry - 200f, min(tmp[2], 90f), now,
                    spec.coinText.x - 46f, spec.coinText.y - 16f)
                popups += Popup("+${game.rules.coinPoints}", rx + 70f, ry - 60f, now, 0)
                repeat(8) { spark(rx, ry, 0xFFFFE070.toInt(), now, 150f, 450f, 5f) }
            }
            is TempleEvent.Gem -> {
                popups += Popup("+${game.rules.gemPoints}", rx, ry - 90f, now, 3)
                repeat(18) { spark(rx, ry, if (it % 2 == 0) 0xFFE070FF.toInt() else 0xFFFFFFFF.toInt(), now, 200f, 620f, 6f) }
            }
            is TempleEvent.Pickup -> {
                powerAt[e.power.ordinal] = now
                val d = spec.powerDiscs[e.power.ordinal]
                flyers += Flyer(POWER_SPRITES[e.power.ordinal], rx, ry - 200f, 110f, now, d[0], d[1])
                callout("+1 ${POWER_NAMES[e.power.ordinal]}", rx, ry - 110f, now, 2)
            }
            is TempleEvent.Hit -> {
                hitAt = now
                if (e.heartsLeft in 0..1) heartLostAt[e.heartsLeft] = now
                callout(if (e.heartsLeft > 0) "OUCH!" else "CAUGHT!", rx, ry - 140f, now, 1)
                burst(e.obj, game, now)
            }
            is TempleEvent.Blocked -> {
                blockAt = now
                callout("BLOCKED!", rx, ry - 140f, now, 2)
                burst(e.obj, game, now)
                repeat(26) { spark(rx, ry, 0xFF7FD8FF.toInt(), now, 260f, 820f, 7f) }
            }
            is TempleEvent.Smash -> {
                callout("SMASH! +${game.rules.smashPoints}", rx, ry - 140f, now, 4)
                burst(e.obj, game, now)
            }
            is TempleEvent.Activated -> {
                powerAt[e.power.ordinal] = now
                val col = POWER_COLORS[e.power.ordinal]
                repeat(30) { spark(rx, ry, col, now, 220f, 760f, 7f) }
                callout(POWER_NAMES[e.power.ordinal].uppercase() + "!", rx, ry - 140f, now, 2)
            }
            is TempleEvent.SectionStart -> {
                bannerSection = e.index; bannerAt = now; bannerBonus = e.bonusSeconds
            }
            is TempleEvent.Star -> if (e.count in 1..3) starAt[e.count - 1] = now
            is TempleEvent.Go -> goAt = now
            is TempleEvent.Complete -> repeat(80) {
                spark(spec.stageW * (0.15f + 0.7f * rnd.nextFloat()), spec.stageH * 0.42f,
                    listOf(0xFFFFD040, 0xFFFF8030, 0xFFFF60C0, 0xFF80FF60).map { c -> c.toInt() }[it % 4], now, 300f, 1100f, 8f, gravity = 900f)
            }
            else -> Unit
        }
        game.events.clear()

        val dt = (now - lastTime).coerceIn(0f, 0.1f)
        lastTime = now
        // Embers drifting up from the lava on both sides.
        emberCarry += dt * 26f
        while (emberCarry >= 1f) {
            emberCarry -= 1f
            val side = if (rnd.nextBoolean()) 1f else -1f
            val x = spec.vanishX + side * (230f + rnd.nextFloat() * 420f)
            val y = 780f + rnd.nextFloat() * 900f
            particles += Particle(x, y, (rnd.nextFloat() - 0.5f) * 40f, -60f - rnd.nextFloat() * 110f,
                2.5f + rnd.nextFloat() * 3.5f, if (rnd.nextInt(3) == 0) 0xFFFFF0A0.toInt() else 0xFFFFA030.toInt(), now, 1.4f + rnd.nextFloat())
        }
        // Dust kicked up by the runner's feet.
        if (!game.over && game.speed > 0f) {
            dustCarry += dt * (10f + game.speed)
            while (dustCarry >= 1f) {
                dustCarry -= 1f
                particles += Particle(rx + (rnd.nextFloat() - 0.4f) * 60f, spec.playerY - 4f, (rnd.nextFloat() - 0.5f) * 120f,
                    60f + rnd.nextFloat() * 120f, 7f + rnd.nextFloat() * 8f, 0x66D8B088, now, 0.45f, -120f)
            }
        }
        val t = now
        particles.removeAll { t - it.start > it.life }
        popups.removeAll { t - it.start > 1.0f }
        flyers.removeAll { t - it.start > 0.6f }
        debris.removeAll { t - it.start > 0.7f }
    }

    private fun burst(o: Placed, game: TempleGame, now: Float) {
        itemScreen(o, game, tmp)
        val x = tmp[0]
        val y = tmp[1] - tmp[2] * 0.4f
        debris += Debris(if (o.kind == Item.BOULDER) "boulder" else "block", x, y, tmp[2], now, if (x < spec.vanishX) -1f else 1f)
        repeat(26) {
            val col = if (o.kind == Item.BOULDER) (if (it % 3 == 0) 0xFFFFB030.toInt() else 0xFF5A3A2A.toInt())
            else if (it % 3 == 0) 0xFFE06040.toInt() else 0xFF8A6A50.toInt()
            spark(x, y, col, now, 300f, 1000f, 9f, gravity = 1600f)
        }
    }

    /** Big callouts stack instead of overlapping. */
    private fun callout(text: String, x: Float, y: Float, now: Float, style: Int) {
        val active = popups.count { it.style > 0 && now - it.start < 0.7f }
        popups += Popup(text, x.coerceIn(220f, spec.stageW - 220f), y - active * 74f, now, style)
    }

    private fun spark(x: Float, y: Float, color: Int, t0: Float, vMin: Float, vMax: Float, size: Float, gravity: Float = 0f) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val sp = vMin + rnd.nextFloat() * (vMax - vMin)
        particles += Particle(x, y, cos(a) * sp, sin(a) * sp, size * (0.6f + rnd.nextFloat() * 0.8f), color, t0,
            0.4f + rnd.nextFloat() * 0.35f, gravity)
    }

    // ---- frame --------------------------------------------------------------------------------

    fun draw(c: Canvas, game: TempleGame) {
        val now = game.time
        cam = camLanes(game)
        val shake = shakeOffset(now)
        c.save()
        c.translate(shake, shake * 0.6f)
        drawGround(c, game)
        drawBackdrop(c, now)
        drawProps(c, game, near = false)
        drawItems(c, game, now)
        drawRunner(c, game, now)
        drawProps(c, game, near = true)
        drawDebris(c, now)
        drawParticles(c, now)
        c.restore()
        drawHitTint(c, now)
        drawHud(c, game, now)
        drawFlyers(c, now)
        drawPopups(c, now)
        drawBanner(c, game, now)
        drawIntro(c, game, now)
        when {
            game.over -> drawEndCard(c, game, now)
            game.paused -> drawPaused(c)
        }
    }

    private fun shakeOffset(now: Float): Float {
        val k = now - max(hitAt, blockAt)
        return if (k in 0f..0.35f) sin(k * 90f) * 16f * (1f - k / 0.35f) else 0f
    }

    // ---- ground ---------------------------------------------------------------------------------

    /** Maps plane coordinates (X * KP, -dz * KP) onto the screen for the current camera. */
    private fun setPlane() {
        val w = 40f
        val near = -2.6f
        val far = GROUND_FAR
        val src = floatArrayOf(-w * KP, -near * KP, w * KP, -near * KP, w * KP, -far * KP, -w * KP, -far * KP)
        val dstPts = floatArrayOf(
            spec.xAt(-w, near, cam), spec.yAt(near), spec.xAt(w, near, cam), spec.yAt(near),
            spec.xAt(w, far, cam), spec.yAt(far), spec.xAt(-w, far, cam), spec.yAt(far),
        )
        planeMatrix.setPolyToPoly(src, 0, dstPts, 0, 4)
    }

    private fun drawGround(c: Canvas, game: TempleGame) {
        setPlane()
        val near = -2.6f
        val far = GROUND_FAR
        val t = game.time
        c.save()
        c.concat(planeMatrix)
        // Molten lava: the tile laid flat, scrolling with the run, and a second, larger layer
        // drifting across it (screen-blended) so the lava shimmers and flows.
        val lw = assets.lava.width.toFloat()
        val lh = assets.lava.height.toFloat()
        val lz = game.dist % spec.lavaZ
        shaderMatrix.setScale(spec.lavaX * KP / lw, -spec.lavaZ * KP / lh)
        shaderMatrix.postTranslate(0f, lz * KP)
        lavaPaint.shader.setLocalMatrix(shaderMatrix)
        c.drawRect(-40f * KP, -far * KP, 40f * KP, -near * KP, lavaPaint)
        shaderMatrix.setScale(spec.lavaX * 1.7f * KP / lw, -spec.lavaZ * 1.7f * KP / lh)
        shaderMatrix.postTranslate(sin(t * 0.35f) * 0.6f * KP + t * 0.18f * KP, lz * KP + sin(t * 0.5f) * 0.4f * KP)
        lavaFlow.shader.setLocalMatrix(shaderMatrix)
        lavaFlow.alpha = (70 + 30 * sin(t * 1.3f)).toInt()
        c.drawRect(-40f * KP, -far * KP, 40f * KP, -near * KP, lavaFlow)
        // The stone path.
        val k = spec.pathK
        val pz = game.dist % spec.pathPeriod
        shaderMatrix.setScale(KP / k, -KP / k)
        shaderMatrix.postTranslate(-spec.pathHalf * KP, -(spec.groundZ0 - pz) * KP)
        pathPaint.shader.setLocalMatrix(shaderMatrix)
        c.drawRect(-spec.pathHalf * KP, -far * KP, spec.pathHalf * KP, -near * KP, pathPaint)
        // The painted opening ground, blended away over the first steps.
        val intro = (1f - game.dist / INTRO_FADE).coerceIn(0f, 1f)
        if (intro > 0f) {
            val ik = spec.introK
            val ih = assets.introGround.height / ik
            shaderMatrix.setScale(KP / ik, -KP / ik)
            shaderMatrix.postTranslate(-spec.introHalf * KP, -(spec.groundZ0 - game.dist) * KP)
            introPaint.shader.setLocalMatrix(shaderMatrix)
            introPaint.alpha = (255 * intro).roundToInt()
            val z0 = spec.groundZ0 - game.dist
            c.drawRect(-spec.introHalf * KP, -(z0 + ih) * KP, spec.introHalf * KP, -max(z0, near) * KP, introPaint)
        }
        c.restore()
    }

    private fun drawBackdrop(c: Canvas, now: Float) {
        c.drawBitmap(assets.backdrop, -spec.marginX, -spec.marginY, bmp)
        // The golem's eyes smoulder.
        val pulse = 0.55f + 0.45f * sin(now * 2.6f)
        for (ex in floatArrayOf(GOLEM_EYE_L, GOLEM_EYE_R)) glow(c, ex, GOLEM_EYE_Y, 34f, 0xFFFFC040.toInt(), (150 * pulse).toInt())
        glow(c, 484f, 660f, 70f, 0xFFFF9020.toInt(), (60 + 50 * pulse).toInt())
    }

    // ---- side scenery ------------------------------------------------------------------------

    /**
     * The painted side scenery, repeating along the path (each side on its own period), as
     * separate props that keep their shape as they pass. Every other repeat is mirrored for
     * variety. The first set stands exactly where the painting has it.
     */
    private fun drawProps(c: Canvas, game: TempleGame, near: Boolean) {
        val started = ((game.time - game.rules.introSeconds) / 0.6f).coerceIn(0f, 1f)
        val items = ArrayList<Triple<Float, PropSpec, Int>>()
        for (p in spec.props) {
            val period = if (p.side < 0) LEFT_PERIOD else RIGHT_PERIOD
            val k0 = floor((game.dist - 2.5f - p.z) / period).toInt().coerceAtLeast(0)
            val k1 = floor((game.dist + PROP_FAR - p.z) / period).toInt()
            for (k in k0..k1) {
                val dz = p.z + k * period - game.dist
                if (dz <= -2.5f || dz >= PROP_FAR) continue
                if ((dz < 0.2f) != near) continue
                items += Triple(dz, p, k)
            }
        }
        items.sortByDescending { it.first }
        for ((dz, p, k) in items) {
            val s = spec.scaleAt(dz)
            val w = p.w * spec.laneW * s
            val h = p.h * spec.laneW * s
            val cx = spec.xAt(p.x, dz, cam)
            val bottom = spec.yAt(dz)
            var a = ((PROP_FAR - dz) / 3f).coerceIn(0f, 1f)
            if (k > 0) a *= started
            if (a <= 0f) continue
            fade.alpha = (255 * a).roundToInt()
            c.save()
            if (k % 2 == 1) c.scale(-1f, 1f, cx, bottom)
            dst.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
            c.drawBitmap(assets.props.getValue(p.name), null, dst, fade)
            c.restore()
        }
    }

    // ---- items ---------------------------------------------------------------------------------

    private fun drawItems(c: Canvas, game: TempleGame, now: Float) {
        val list = game.objects.filter {
            if (game.isTaken(it)) return@filter false
            val dz = drawDz(it, it.z - game.dist)
            dz > -1.4f && dz < VISIBLE_FAR
        }.sortedByDescending { drawDz(it, it.z - game.dist) }
        for (o in list) drawItem(c, o, game, now)
    }

    private fun drawItem(c: Canvas, o: Placed, game: TempleGame, now: Float) {
        val rawDz = o.z - game.dist
        val dz = drawDz(o, rawDz)
        val s = spec.scaleAt(dz)
        var x = spec.xAt(o.x, dz, cam)
        val y = spec.yAt(dz)
        val w = itemWidth(o, dz)
        val alpha = (255 * ((VISIBLE_FAR - dz) / 4f).coerceIn(0f, 1f)).toInt()
        // A magnet tugs coins and gems toward the runner as they come close.
        if ((o.kind == Item.COIN || o.kind == Item.GEM) && game.active(Power.MAGNET) && rawDz in 0f..5f &&
            abs(o.x - game.x) <= game.rules.magnetReach) {
            val k = 1f - rawDz / 5f
            x += (runnerX(game) - x) * k * k
        }
        when (o.kind) {
            Item.COIN -> {
                // face-on while the opening frame holds, then spinning
                val spin = if (game.introActive) 1f else cos((now - game.rules.introSeconds) * 4f + o.z * 0.35f)
                val cw = w * max(0.16f, abs(spin))
                val cy = y - w * (0.75f + 0.08f * sin(now * 3f + o.z))
                glow(c, x, cy, w * 0.8f, 0xFFFFE070.toInt(), (alpha * 0.5f).toInt())
                fade.alpha = alpha
                dst.set(x - cw / 2f, cy - w / 2f, x + cw / 2f, cy + w / 2f)
                c.drawBitmap(assets.sprite.getValue("coin"), null, dst, fade)
            }
            Item.GEM -> {
                val b = assets.sprite.getValue("gem")
                val h = w * b.height / b.width
                val cy = y - h * (0.62f + 0.05f * sin(now * 2.5f + o.z))
                glow(c, x, cy, w * 0.95f, 0xFFE040FF.toInt(), (alpha * (0.5f + 0.2f * sin(now * 5f))).toInt())
                fade.alpha = alpha
                dst.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
                c.drawBitmap(b, null, dst, fade)
            }
            Item.BOULDER -> {
                val b = assets.sprite.getValue("boulder")
                val h = w * b.height / b.width
                // shadow and lava glow under it, then the rock, bouncing as it rolls
                fill.shader = null
                fill.color = 0xFF000000.toInt(); fill.alpha = (alpha * 0.35f).toInt()
                dst.set(x - w * 0.42f, y - 10f * s, x + w * 0.42f, y + 14f * s)
                c.drawOval(dst, fill)
                glow(c, x, y - h * 0.45f, w * 0.75f, 0xFFFF8020.toInt(), (alpha * 0.45f).toInt())
                val bounce = abs(sin(dz * 1.6f)) * 18f * s
                fade.alpha = alpha
                c.save()
                c.rotate(sin(dz * 0.9f) * 14f, x, y - h / 2f - bounce)
                dst.set(x - w / 2f, y - h - bounce, x + w / 2f, y - bounce)
                c.drawBitmap(b, null, dst, fade)
                c.restore()
            }
            Item.BLOCK -> {
                val b = assets.props.getValue("chevron_l")
                val h = w * b.height / b.width
                glow(c, x, y - h * 0.5f, w * 0.7f, 0xFFFF4020.toInt(), (alpha * (0.3f + 0.15f * sin(now * 6f))).toInt())
                fade.alpha = alpha
                dst.set(x - w / 2f, y - h, x + w / 2f, y)
                c.drawBitmap(b, null, dst, fade)
            }
            Item.LIGHTNING, Item.SHIELD, Item.MAGNET -> {
                val i = when (o.kind) { Item.LIGHTNING -> 0; Item.SHIELD -> 1; else -> 2 }
                val b = assets.sprite.getValue(POWER_SPRITES[i])
                val pulse = 1f + 0.08f * sin(now * 5f)
                val ww = w * pulse
                val cy = y - ww * 0.85f
                glow(c, x, cy, ww * 0.9f, POWER_COLORS[i], (alpha * 0.6f).toInt())
                fade.alpha = alpha
                // the disc only (crop the count badge off the button art)
                val d = spec.powerDiscs[i]
                val box = spec.sprites.getValue(POWER_SPRITES[i])
                val sr = android.graphics.Rect((d[0] - d[2] - box.l).toInt(), (d[1] - d[2] - box.t).toInt(),
                    (d[0] + d[2] - box.l).toInt(), (d[1] + d[2] - box.t).toInt())
                dst.set(x - ww / 2f, cy - ww / 2f, x + ww / 2f, cy + ww / 2f)
                c.drawBitmap(b, sr, dst, fade)
            }
        }
    }

    // ---- runner -----------------------------------------------------------------------------------

    private fun drawRunner(c: Canvas, game: TempleGame, now: Float) {
        val r = runnerRect(game)
        val running = game.speed > 0f
        val stride = if (running) now * (6f + game.speed * 0.35f) else 0f
        val bob = if (running) -abs(sin(stride)) * 14f else sin(now * 3f) * 3f
        val lean = ((game.targetX - game.x) * 9f).coerceIn(-10f, 10f) + if (running) sin(stride) * 2.5f else 0f
        val cx = r.centerX()
        val feet = spec.playerY
        // shadow on the path
        fill.shader = RadialGradient(cx + 8f, feet + 6f, 110f, intArrayOf(0x88000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        dst.set(cx - 115f, feet - 26f, cx + 130f, feet + 38f)
        c.drawOval(dst, fill)
        fill.shader = null
        if (game.active(Power.LIGHTNING)) {
            glow(c, cx, r.centerY(), 230f, 0xFFFFE040.toInt(), (150 + 60 * sin(now * 20f)).toInt())
            // speed streaks
            stroke.color = 0xFFFFF4C0.toInt(); stroke.strokeWidth = 5f
            for (i in 0 until 10) {
                val sx = ((i * 97 + (now * 2400f).toInt()) % 941).toFloat()
                val sy = 700f + (i * 131 % 900)
                stroke.alpha = 120
                c.drawLine(sx, sy, spec.vanishX + (sx - spec.vanishX) * 0.9f, sy - (sy - spec.horizon) * 0.1f, stroke)
            }
        }
        val flicker = game.invulnerable && !game.over && ((now * 14f).toInt() % 2 == 0)
        bmp.alpha = if (flicker) 120 else 255
        c.save()
        c.translate(0f, bob)
        c.rotate(lean, cx, feet)
        if (running) c.scale(1f + 0.02f * sin(stride * 2f), 1f - 0.02f * sin(stride * 2f), cx, feet)
        c.drawBitmap(assets.sprite.getValue("runner"), null, r, bmp)
        c.restore()
        bmp.alpha = 255
        val bcx = cx
        val bcy = r.top + r.height() * 0.48f + bob
        if (game.active(Power.SHIELD)) {
            val left = game.powerUntil[Power.SHIELD.ordinal] - now
            if (left > 2f || ((now * 8f).toInt() % 2 == 0)) {
                val rr = 200f + 6f * sin(now * 6f)
                fill.shader = RadialGradient(bcx, bcy, rr, intArrayOf(0x0040C0FF, 0x2240C0FF, 0x9960D8FF.toInt()),
                    floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
                dst.set(bcx - rr * 0.85f, bcy - rr * 1.12f, bcx + rr * 0.85f, bcy + rr * 1.12f)
                c.drawOval(dst, fill)
                fill.shader = null
                stroke.color = 0xFFBFF0FF.toInt(); stroke.alpha = 200; stroke.strokeWidth = 5f
                c.drawOval(dst, stroke)
            }
        }
        val kb = now - blockAt
        if (kb in 0f..0.45f) {
            val k = kb / 0.45f
            stroke.color = 0xFF9BE6FF.toInt(); stroke.alpha = (255 * (1 - k)).toInt(); stroke.strokeWidth = 14f * (1 - k) + 2f
            c.drawCircle(bcx, bcy, 200f * (0.8f + 0.7f * k), stroke)
        }
        if (game.active(Power.MAGNET)) {
            for (i in 0 until 3) {
                val k = ((now * 1.2f + i / 3f) % 1f)
                stroke.color = 0xFFFF6060.toInt(); stroke.alpha = (160 * (1 - k)).toInt(); stroke.strokeWidth = 4f
                c.drawCircle(bcx, bcy, 260f * (1 - k) + 60f, stroke)
            }
        }
    }

    private fun drawDebris(c: Canvas, now: Float) {
        for (d in debris) {
            val k = ((now - d.start) / 0.7f).coerceIn(0f, 1f)
            val b = if (d.sprite == "boulder") assets.sprite.getValue("boulder") else assets.props.getValue("chevron_l")
            val h = d.w * b.height / b.width
            fade.alpha = (255 * (1 - k)).toInt()
            c.save()
            c.translate(d.x + d.dir * 320f * k, d.y - 260f * k + 500f * k * k)
            c.rotate(d.dir * 240f * k)
            c.scale(1f - 0.4f * k, 1f - 0.4f * k)
            dst.set(-d.w / 2f, -h / 2f, d.w / 2f, h / 2f)
            c.drawBitmap(b, null, dst, fade)
            c.restore()
        }
    }

    private fun drawParticles(c: Canvas, now: Float) {
        fill.shader = null
        for (p in particles) {
            val t = now - p.start
            if (t < 0f || t > p.life) continue
            val k = t / p.life
            val x = p.x + p.vx * t
            val y = p.y + p.vy * t + 0.5f * p.gravity * t * t
            fill.color = p.color
            fill.alpha = (Color.alpha(p.color) * (1f - k)).toInt()
            c.drawCircle(x, y, p.r * (1f - 0.4f * k), fill)
        }
    }

    private fun drawFlyers(c: Canvas, now: Float) {
        for (f in flyers) {
            val k = ((now - f.start) / 0.6f).coerceIn(0f, 1f)
            val e = k * k
            val x = f.x + (f.tx - f.x) * e
            val y = f.y + (f.ty - f.y) * e - sin(k * PI.toFloat()) * 140f
            val r = f.size * 0.5f * (1f - 0.55f * k)
            dst.set(x - r, y - r, x + r, y + r)
            if (f.sprite == "coin") c.drawBitmap(assets.sprite.getValue("coin"), null, dst, bmp)
            else {
                val i = POWER_SPRITES.indexOf(f.sprite)
                val d = spec.powerDiscs[i]
                val box = spec.sprites.getValue(f.sprite)
                val sr = android.graphics.Rect((d[0] - d[2] - box.l).toInt(), (d[1] - d[2] - box.t).toInt(),
                    (d[0] + d[2] - box.l).toInt(), (d[1] + d[2] - box.t).toInt())
                c.drawBitmap(assets.sprite.getValue(f.sprite), sr, dst, bmp)
            }
        }
    }

    private fun drawHitTint(c: Canvas, now: Float) {
        val k = now - hitAt
        if (k !in 0f..0.5f) return
        val a = (160 * (1f - k / 0.5f)).toInt()
        fill.shader = RadialGradient(spec.stageW / 2f, spec.stageH / 2f, spec.stageH * 0.75f,
            intArrayOf(0x00FF2020, withAlpha(0xFFFF2020.toInt(), a)), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(-spec.marginX, -spec.marginY, spec.stageW + spec.marginX, spec.stageH + 400f, fill)
        fill.shader = null
    }

    // ---- HUD --------------------------------------------------------------------------------

    private fun drawHud(c: Canvas, game: TempleGame, now: Float) {
        // Score rolls up; coins count.
        if (shownScore < 0f) shownScore = game.score.toFloat()
        val target = game.score.toFloat()
        shownScore = if (abs(target - shownScore) < 1f) target else shownScore + (target - shownScore) * 0.25f
        val st = spec.scoreText
        text.cream(c, "%,d".format(shownScore.roundToInt()), st.x, st.y, st.size)
        val ct = spec.coinText
        text.white(c, "${game.coins}", ct.x, ct.y, ct.size)
        // Hearts: a lost heart pops and turns dark.
        for ((i, b) in spec.hearts.withIndex()) {
            val alive = i < game.hearts
            val k = (now - heartLostAt[i]).let { if (it in 0f..0.5f) it / 0.5f else 1f }
            dst.set(b.l, b.t, b.r, b.b)
            if (!alive) c.drawBitmap(assets.sprite.getValue("heart_empty"), null, dst, bmp)
            if (alive || k < 1f) {
                val sc = if (alive) 1f + 0.06f * sin(now * 4f) else 1f + 0.8f * k
                fade.alpha = if (alive) 255 else (255 * (1 - k)).toInt()
                c.save()
                c.scale(sc, sc, b.cx, b.cy)
                c.drawBitmap(assets.sprite.getValue("heart"), null, dst, fade)
                c.restore()
            }
        }
        // Stars: earned ones are gold; a new star pops in.
        val stars = game.stars
        for ((i, b) in spec.stars.withIndex()) {
            dst.set(b.l, b.t, b.r, b.b)
            c.drawBitmap(assets.sprite.getValue("star_empty"), null, dst, bmp)
            if (i < stars) {
                val k = (now - starAt[i]).let { if (it in 0f..0.5f) it / 0.5f else 1f }
                val sc = if (k < 1f) 1f + 0.6f * sin(k * PI.toFloat()) else 1f
                c.save()
                c.scale(sc, sc, b.cx, b.cy)
                c.drawBitmap(assets.sprite.getValue("star_gold"), null, dst, bmp)
                c.restore()
                if (k < 1f) glow(c, b.cx, b.cy, 70f, 0xFFFFE070.toInt(), (200 * (1 - k)).toInt())
            }
        }
        // Timer: green flash on a checkpoint bonus, pulses when low.
        val secs = game.timerSeconds
        val bonus = (now - game.lastCheckpointAt).let { if (it in 0f..0.8f) 1f - it / 0.8f else 0f }
        val coral = 0xFFFF6B6B.toInt()
        val col = when {
            bonus > 0f -> lerp(coral, 0xFF8CFF6A.toInt(), bonus)
            secs <= 10 && !game.over -> lerp(coral, 0xFFFFFFFF.toInt(), 0.5f + 0.5f * sin(now * 10f))
            else -> coral
        }
        val tt = spec.timerText
        text.white(c, "%d:%02d".format(secs / 60, secs % 60), tt.x, tt.y, tt.size * (1f + 0.1f * bonus), col)
        // Power-up buttons with their counts; an active one glows with its time running down.
        for (i in 0 until 3) {
            val name = POWER_SPRITES[i]
            val box = spec.sprites.getValue(name)
            val d = spec.powerDiscs[i]
            val active = game.time < game.powerUntil[i]
            val count = game.powers[i]
            val pop = (now - powerAt[i]).let { if (it in 0f..0.35f) 1f + 0.18f * sin(it / 0.35f * PI.toFloat()) else 1f }
            if (active) glow(c, d[0], d[1], d[2] * 1.6f, POWER_COLORS[i], (170 + 60 * sin(now * 8f)).toInt())
            fade.alpha = if (count > 0 || active) 255 else 120
            c.save()
            c.scale(pop, pop, d[0], d[1])
            c.drawBitmap(assets.sprite.getValue(name), box.l, box.t, fade)
            c.restore()
            if (active) {
                val total = when (i) { 0 -> game.rules.lightningSeconds; 1 -> game.rules.shieldSeconds; else -> game.rules.magnetSeconds }
                val left = ((game.powerUntil[i] - now) / total).coerceIn(0f, 1f)
                stroke.color = 0xFFFFFFFF.toInt(); stroke.alpha = 230; stroke.strokeWidth = 8f
                dst.set(d[0] - d[2] - 6f, d[1] - d[2] - 6f, d[0] + d[2] + 6f, d[1] + d[2] + 6f)
                c.drawArc(dst, -90f, 360f * left, false, stroke)
            }
            val bdg = spec.powerBadges[i]
            text.white(c, "$count", bdg[0] + spec.countDx, bdg[1] + spec.countDy, spec.countSize,
                if (count > 0) 0xFFFFFFFF.toInt() else 0xFF9AA4B8.toInt())
        }
        // Arrow buttons.
        for ((name, pressed) in listOf("arrow_left" to leftPressed, "arrow_right" to rightPressed)) {
            val b = spec.sprites.getValue(name)
            val k = if (pressed) 0.9f else 1f
            c.save()
            c.scale(k, k, b.cx, b.cy)
            c.drawBitmap(assets.sprite.getValue(name), b.l, b.t, bmp)
            if (pressed) glow(c, b.cx, b.cy, b.w * 0.55f, 0xFFFFFFFF.toInt(), 80)
            c.restore()
        }
    }

    private fun drawPopups(c: Canvas, now: Float) {
        for (p in popups) {
            val k = ((now - p.start) / 1.0f).coerceIn(0f, 1f)
            val y = p.y - 90f * k
            val a = (255 * (1f - k * k)).toInt()
            val size = (if (p.style == 0) 40f else if (p.style == 3) 50f else 62f) * (1f + 0.3f * (1f - min(1f, k * 5f)))
            when (p.style) {
                0 -> text.gold(c, p.text, p.x, y, size, rim = false, alpha = a)
                1 -> text.white(c, p.text, p.x, y, size, 0xFFFF4A3A.toInt(), Paint.Align.CENTER, alpha = a)
                3 -> text.white(c, p.text, p.x, y, size, 0xFFF0A0FF.toInt(), Paint.Align.CENTER, alpha = a)
                4 -> text.gold(c, p.text, p.x, y, size, alpha = a)
                else -> text.blue(c, p.text, p.x, y, size, alpha = a)
            }
        }
    }

    private fun drawBanner(c: Canvas, game: TempleGame, now: Float) {
        val k = now - bannerAt
        if (bannerSection < 0 || k !in 0f..2.2f) return
        val inK = min(1f, k / 0.25f)
        val outK = ((2.2f - k) / 0.35f).coerceIn(0f, 1f)
        val a = (255 * min(inK, outK)).toInt()
        val y = 470f - 40f * (1f - inK)
        val s = TempleCourse.sections[bannerSection]
        val title = s.name.uppercase()
        val w = text.measure(title, 62f) + 120f
        fill.shader = null
        fill.color = 0xCC2A0E04.toInt(); fill.alpha = (0xCC * a / 255)
        dst.set(spec.stageW / 2f - w / 2f, y - 92f, spec.stageW / 2f + w / 2f, y + 34f)
        c.drawRoundRect(dst, 26f, 26f, fill)
        stroke.color = 0xFFFFB040.toInt(); stroke.alpha = a; stroke.strokeWidth = 4f
        c.drawRoundRect(dst, 26f, 26f, stroke)
        text.white(c, "SECTION ${bannerSection + 1}", spec.stageW / 2f, y - 50f, 34f, align = Paint.Align.CENTER, alpha = a)
        text.gold(c, title, spec.stageW / 2f, y + 12f, 56f, alpha = a)
        if (bannerBonus > 0f) {
            text.white(c, "+${bannerBonus.roundToInt()}s", spec.timerText.x + 55f, spec.timerText.y + 66f, 42f, 0xFF8CFF6A.toInt(),
                Paint.Align.CENTER, alpha = a)
        }
    }

    /** "Ready" while the opening frame holds, "GO!" as the run starts, and how to play. */
    private fun drawIntro(c: Canvas, game: TempleGame, now: Float) {
        if (game.introActive) {
            val a = (255 * min(1f, now / 0.25f)).toInt()
            text.white(c, "READY?", spec.stageW / 2f, 690f, 70f, align = Paint.Align.CENTER, alpha = a)
        }
        val k = now - goAt
        if (k in 0f..0.8f) {
            val a = (255 * (1f - k / 0.8f)).toInt()
            text.gold(c, "GO!", spec.stageW / 2f, 720f, 130f * (1f + 0.3f * k), alpha = a)
        }
        if (now < game.rules.introSeconds + 3.2f && !game.over) {
            val a = (220 * min(1f, (game.rules.introSeconds + 3.2f - now) / 0.6f)).toInt()
            text.white(c, "Arrows or swipe to dodge", spec.stageW / 2f, 1618f, 34f, align = Paint.Align.CENTER, alpha = a)
        }
    }

    // ---- overlays -----------------------------------------------------------------------------

    private fun drawPaused(c: Canvas) {
        c.drawColor(0x8C000000.toInt())
        text.gold(c, "Paused", spec.stageW / 2f, spec.stageH * 0.42f, 96f)
        text.white(c, "Tap to resume", spec.stageW / 2f, spec.stageH * 0.42f + 76f, 40f, align = Paint.Align.CENTER)
    }

    private fun drawEndCard(c: Canvas, game: TempleGame, now: Float) {
        val since = now - (game.endedAt.takeIf { it >= 0f } ?: now)
        val a = (min(1f, since / 0.5f) * 255).toInt()
        c.drawColor(withAlpha(0xFF180804.toInt(), (0.8f * a).toInt()))
        val cx = spec.stageW / 2f
        val top = spec.stageH * 0.26f
        val won = game.won
        when (game.phase) {
            TemplePhase.COMPLETE -> {
                text.gold(c, "TEMPLE", cx, top, 110f, alpha = a)
                text.blue(c, "ESCAPED!", cx, top + 104f, 96f, alpha = a)
            }
            TemplePhase.CAUGHT -> {
                text.gold(c, "CAUGHT!", cx, top + 40f, 120f, alpha = a)
            }
            else -> {
                text.gold(c, "OUT OF", cx, top, 100f, alpha = a)
                text.gold(c, "TIME!", cx, top + 100f, 100f, alpha = a)
            }
        }
        val earned = if (won) game.stars else 0
        for (i in 0 until 3) {
            val t0 = 0.5f + i * 0.35f
            val k = ((since - t0) / 0.3f).coerceIn(0f, 1f)
            val gold = i < earned
            val b = assets.sprite.getValue(if (gold && k > 0f) "star_gold" else "star_empty")
            val sz = 150f * (if (gold) 0.6f + 0.4f * overshoot(k) else 1f)
            val sx = cx + (i - 1) * 170f
            val sy = top + 250f - (if (i == 1) 30f else 0f)
            dst.set(sx - sz / 2f, sy - sz / 2f, sx + sz / 2f, sy + sz / 2f)
            fade.alpha = a
            c.drawBitmap(b, null, dst, fade)
        }
        val rows = listOf(
            "Coins" to "${game.coinsTaken} / ${game.totalCoins}",
            "Gems" to "${game.gemsTaken} / ${game.totalGems}",
            "Hearts left" to "${game.hearts}",
            "Time bonus" to (if (won) "+${game.timerSeconds * game.rules.timeBonusPerSecond}" else "—"),
            "Heart bonus" to (if (won) "+${game.hearts * game.rules.heartBonus}" else "—"),
        )
        var y = top + 420f
        fill.shader = null
        fill.color = 0xE62A0E04.toInt(); fill.alpha = (0xE6 * a / 255)
        dst.set(cx - 350f, y - 70f, cx + 350f, y + rows.size * 64f + 80f)
        c.drawRoundRect(dst, 36f, 36f, fill)
        stroke.color = 0xFFFFB040.toInt(); stroke.alpha = a; stroke.strokeWidth = 4f
        c.drawRoundRect(dst, 36f, 36f, stroke)
        for ((label, value) in rows) {
            text.white(c, label, cx - 300f, y, 44f, alpha = a)
            text.white(c, value, cx + 300f, y, 44f, align = Paint.Align.RIGHT, alpha = a)
            y += 64f
        }
        text.white(c, "Score", cx - 300f, y + 40f, 54f, alpha = a)
        text.cream(c, "%,d".format(game.score), cx + 300f, y + 40f, 72f, Paint.Align.RIGHT)
        if (since > 1.6f) {
            val blink = 0.6f + 0.4f * sin(now * 4f)
            text.white(c, if (won) "Tap to run again" else "Tap to try again", cx, y + 170f, 44f,
                align = Paint.Align.CENTER, alpha = (a * blink).toInt())
        }
    }

    private fun overshoot(k: Float): Float = if (k <= 0f) 0f else 1f + 0.25f * sin(k * PI.toFloat()) * (1f - k)

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        if (alpha <= 0 || r <= 1f) return
        fill.shader = RadialGradient(x, y, r, intArrayOf(withAlpha(color, alpha.coerceIn(0, 255)), withAlpha(color, 0)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

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
        /** Plane units per lane for the ground's perspective mapping. */
        const val KP = 100f
        const val GROUND_FAR = 40f
        /** How far ahead (units) items are drawn: they appear from the golem's feet. */
        const val VISIBLE_FAR = 26f
        const val PROP_FAR = 14f
        const val LEFT_PERIOD = 7f
        const val RIGHT_PERIOD = 6.3f
        /** The opening's painted ground blends away over this many units. */
        const val INTRO_FADE = 2.5f
        /** Boulders roll at half the runner's speed toward it: drawn this much farther. */
        const val BOULDER_ROLL = 1.5f
        const val CAM_FOLLOW = 0.25f
        /** The painted runner's centre x in the reference. */
        const val PAINTED_RUNNER_X = 445f
        const val GOLEM_EYE_L = 452f
        const val GOLEM_EYE_R = 528f
        const val GOLEM_EYE_Y = 566f
        val POWER_SPRITES = listOf("pu_lightning", "pu_shield", "pu_magnet")
        val POWER_NAMES = listOf("Lightning", "Shield", "Magnet")
        val POWER_COLORS = intArrayOf(0xFFFFD040.toInt(), 0xFF60C8FF.toInt(), 0xFFFF5050.toInt())
    }
}
