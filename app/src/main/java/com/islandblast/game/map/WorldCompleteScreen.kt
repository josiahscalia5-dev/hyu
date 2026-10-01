package com.islandblast.game.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.islandblast.game.app.FrameLoop
import com.islandblast.game.app.GameFlow
import com.islandblast.game.app.SafeArea
import com.islandblast.game.app.Screen
import com.islandblast.game.levels.AssetCache
import com.islandblast.game.levels.LevelDef
import com.islandblast.game.levels.Progress
import com.islandblast.game.levels.WorldDef
import com.islandblast.game.ui.ButtonStyle
import com.islandblast.game.ui.Icon
import com.islandblast.game.ui.UiKit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shown after the Level Complete card of a world's last level: "World 1 Complete!"
 * with every level of the world and the stars earned, then "World 2 Unlocked!".
 * Continue returns to the world's map, where the gate to the next world is now open.
 */
class WorldCompleteScreen(private val flow: GameFlow, val world: WorldDef, private val finished: LevelDef) : Screen {
    private val mapKey = "map:${world.id}"
    val card = WorldCompleteView(flow.context, world, flow.ui, flow.progress,
        if (AssetCache.contains(mapKey)) AssetCache.get(mapKey) { MapArt(flow.context.assets, world.id) } else null,
    ) { flow.openMap(world, finished) }
    override val view: View get() = card

    override fun start() = card.loop.start()
    override fun stop() = card.loop.stop()

    override fun onBack(): Boolean {
        flow.openMap(world, finished)
        return true
    }
}

@SuppressLint("ViewConstructor")
class WorldCompleteView(
    context: Context,
    val world: WorldDef,
    private val ui: UiKit,
    private val progress: Progress,
    /** The world's map, drawn dimmed behind the cards (a plain sky if it is not loaded). */
    private val art: MapArt?,
    private val onContinue: () -> Unit,
) : View(context) {
    var time = 0f
        private set
    val loop = FrameLoop { dt ->
        time += dt
        invalidate()
    }
    private val safe = SafeArea()
    private var k = 1f
    private val worldCard = RectF()
    private val unlockCard = RectF()
    private val button = RectF()
    private var pressed = false

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()

    /** The world the gate leads to (World 2 for World 1). */
    val nextWorld: Int? = world.gate?.to

    /** The "World 2 Unlocked!" card is up. */
    val unlockShown: Boolean get() = time >= UNLOCK_AT

    /** Continue can be pressed. */
    val ready: Boolean get() = time >= BUTTON_AT + 0.35f

    fun continueCenter(): PointF = PointF(button.centerX(), button.centerY())

    /** Everything that must be on screen, in view pixels (tests check none is cut off). */
    fun mustShow(): Map<String, RectF> = mapOf("world card" to RectF(worldCard), "unlock card" to RectF(unlockCard),
        "continue" to RectF(button))

    fun setSafeInsetsForTest(l: Int, t: Int, r: Int, b: Int) {
        safe.set(l, t, r, b)
        layoutCards()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        safe.apply(insets)
        layoutCards()
        invalidate()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutCards()

    private fun layoutCards() {
        if (width == 0) return
        val area = safe.rect(width, height, 16f)
        // Scale down if the safe area is short (landscape-ish shapes, big cutouts).
        k = min(UiKit.unit(width, height), area.height() / CONTENT_H)
        val cx = area.centerX()
        var y = area.centerY() - CONTENT_H * k / 2f
        worldCard.set(cx - 440f * k, y, cx + 440f * k, y + 720f * k)
        y = worldCard.bottom + 50f * k
        unlockCard.set(cx - 440f * k, y, cx + 440f * k, y + 360f * k)
        y = unlockCard.bottom + 56f * k
        button.set(cx - 300f * k, y, cx + 300f * k, y + 124f * k)
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        drawBackdrop(c, w, h)
        drawRays(c, worldCard.centerX(), worldCard.top + 260f * k)
        // The World Complete card pops in.
        popped(c, worldCard, time / 0.4f) { drawWorldCard(c) }
        // Then World 2 unlocks.
        if (time >= UNLOCK_AT) popped(c, unlockCard, (time - UNLOCK_AT) / 0.4f) { drawUnlockCard(c) }
        if (time >= BUTTON_AT) {
            popped(c, button, (time - BUTTON_AT) / 0.3f) {
                ui.button(c, button, "Continue", ButtonStyle.GREEN, pressed, k, Icon.MAP)
            }
        }
        drawConfetti(c, w, h)
        if (time < 0.25f) {
            fill.shader = null
            fill.color = ((255 * (1f - time / 0.25f)).toInt() shl 24)
            c.drawRect(0f, 0f, w, h, fill)
        }
    }

    private fun drawBackdrop(c: Canvas, w: Float, h: Float) {
        if (art != null) {
            val s = max(w / art.w, h / art.h)
            tmp.set((w - art.w * s) / 2f, (h - art.h * s) / 2f, (w + art.w * s) / 2f, (h + art.h * s) / 2f)
            c.drawBitmap(art.backdrop, null, tmp, bmp)
            fill.shader = null
            fill.color = 0xB0061436.toInt()
            c.drawRect(0f, 0f, w, h, fill)
        } else {
            fill.shader = LinearGradient(0f, 0f, 0f, h, 0xFF0D63B8.toInt(), 0xFF1AB0E0.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
    }

    /** Slowly turning sunbeams behind the World Complete card. */
    private fun drawRays(c: Canvas, cx: Float, cy: Float) {
        val a = min(1f, time / 0.6f)
        val len = 1400f * k
        fill.shader = null
        fill.color = Color.argb((46 * a).toInt(), 255, 230, 120)
        for (i in 0 until RAYS) {
            val t0 = time * 0.15f + i * 2f * PI.toFloat() / RAYS
            val t1 = t0 + PI.toFloat() / RAYS * 0.9f
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(t0) * len, cy + sin(t0) * len)
            path.lineTo(cx + cos(t1) * len, cy + sin(t1) * len)
            path.close()
            c.drawPath(path, fill)
        }
    }

    private fun drawWorldCard(c: Canvas) {
        val r = worldCard
        ui.panel(c, r, k)
        val cx = r.centerX()
        ui.text.white(c, "World ${world.number}", cx, r.top + 110f * k, 64f * k, Color.WHITE, Paint.Align.CENTER, outline = 0.9f)
        ui.text.gold(c, "Complete!", cx, r.top + 240f * k, 124f * k, Paint.Align.CENTER, 0f)
        // The world's name on a pill.
        val ns = 42f * k
        val tw = ui.text.measure(world.name, ns)
        tmp.set(cx - tw / 2f - 30f * k, r.top + 280f * k, cx + tw / 2f + 30f * k, r.top + 350f * k)
        ui.pill(c, tmp, k)
        ui.text.white(c, world.name, cx, tmp.centerY() + ns * 0.36f, ns, Color.WHITE, Paint.Align.CENTER, outline = 0.7f)
        // Every level of the world with the stars it earned; levels still to come are locked.
        val levels = world.levels
        val gap = min(140f * k, (r.width() - 80f * k) / levels.size)
        val rad = min(52f * k, gap * 0.38f)
        val y = r.top + 470f * k
        levels.forEachIndexed { i, lv ->
            val x = cx + (i - (levels.size - 1) / 2f) * gap
            val appear = ((time - 0.35f - i * 0.12f) / 0.25f).coerceIn(0f, 1f)
            if (appear <= 0f) return@forEachIndexed
            c.save()
            c.scale(appear, appear, x, y)
            val style = when {
                !lv.playable -> ButtonStyle.GRAY
                progress.completed(lv) -> ButtonStyle.BLUE
                else -> ButtonStyle.GREEN
            }
            ui.roundButton(c, x, y, rad, style, null, false, k)
            if (lv.playable) {
                ui.text.white(c, "${lv.number}", x, y + rad * 0.38f, rad * 1.05f, Color.WHITE, Paint.Align.CENTER, outline = 1f)
                val stars = progress.stars(lv)
                for (s in 0 until 3) ui.star(c, x + (s - 1) * rad * 0.76f, y + rad * 1.62f, rad * 0.72f, s < stars)
            } else {
                ui.drawIcon(c, Icon.LOCK, x, y - rad * 0.05f, rad * 0.42f, Color.WHITE, 0xFF20263A.toInt(), k)
                ui.text.white(c, "${lv.number}", x, y + rad * 1.62f, rad * 0.5f, 0xFFDDE6F5.toInt(), Paint.Align.CENTER,
                    outline = 0.8f)
            }
            c.restore()
        }
        // Stars earned in the world.
        val total = progress.totalStars(world)
        val max = levels.count { it.playable } * 3
        val label = "$total / $max"
        val ts = 64f * k
        val lw = ui.text.measure(label, ts)
        val sy = r.top + 640f * k
        ui.star(c, cx - lw / 2f - 30f * k, sy - 20f * k, 96f * k, true)
        ui.text.white(c, label, cx - lw / 2f + 40f * k, sy + ts * 0.18f, ts, Color.WHITE, Paint.Align.LEFT, outline = 0.9f)
    }

    private fun drawUnlockCard(c: Canvas) {
        val r = unlockCard
        val next = nextWorld ?: return
        ui.panel(c, r, k)
        val bx = r.left + 150f * k
        val by = r.centerY() - 10f * k
        val since = time - UNLOCK_AT
        ui.roundButton(c, bx, by, 92f * k, ButtonStyle.GREEN, null, false, k)
        ui.text.white(c, "$next", bx, by + 38f * k, 104f * k, Color.WHITE, Paint.Align.CENTER, outline = 1.1f)
        // The padlock springs off the button and fades away.
        val lk = ((since - 0.25f) / 0.6f).coerceIn(0f, 1f)
        if (lk < 1f) {
            val ly = by - 40f * k - 120f * k * lk
            fill.shader = null
            c.saveLayerAlpha(bx - 80f * k, ly - 80f * k, bx + 80f * k, ly + 80f * k, (255 * (1f - lk)).toInt())
            ui.drawIcon(c, Icon.LOCK, bx, ly, 46f * k, Color.WHITE, 0xFF20263A.toInt(), k)
            c.restore()
        }
        val tx = r.left + 290f * k
        ui.text.white(c, "World $next", tx, r.top + 120f * k, 56f * k, Color.WHITE, Paint.Align.LEFT, outline = 0.9f)
        ui.text.gold(c, "Unlocked!", tx, r.top + 230f * k, 104f * k, Paint.Align.LEFT, 0f)
        ui.text.white(c, "New islands coming soon", tx, r.top + 300f * k, 38f * k, 0xFFDDE6F5.toInt(), Paint.Align.LEFT,
            outline = 0.6f)
    }

    /** Draws [block] scaled in from the centre of [box] with a little overshoot. */
    private inline fun popped(c: Canvas, box: RectF, t: Float, block: () -> Unit) {
        if (t <= 0f) return
        val x = t.coerceIn(0f, 1f) - 1f
        val s = 1f + 2.2f * x * x * x + 1.2f * x * x
        c.save()
        c.scale(s, s, box.centerX(), box.centerY())
        block()
        c.restore()
    }

    /** Confetti drifting down over everything. */
    private fun drawConfetti(c: Canvas, w: Float, h: Float) {
        val colors = intArrayOf(0xFFFFD84A.toInt(), 0xFF5CC8FF.toInt(), 0xFF9BF23E.toInt(), 0xFFFF6A8A.toInt(), 0xFFB78CFF.toInt())
        fill.shader = null
        for (i in 0 until CONFETTI) {
            val seed = i * 7919
            val speed = 220f + (seed % 180)
            val y = ((seed % 997) / 997f * h * 1.2f + time * speed * k) % (h * 1.2f) - h * 0.1f
            val x = (seed % 1009) / 1009f * w + sin(time * 1.7f + i) * 30f * k
            fill.color = colors[i % colors.size]
            c.save()
            c.rotate(time * 140f + i * 37f, x, y)
            c.drawRect(x - 9f * k, y - 5f * k, x + 9f * k, y + 5f * k, fill)
            c.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = ready && button.contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit
                // A tap before the cards are up skips the wait.
                if (!ready) time = max(time, BUTTON_AT + 0.35f)
            }
            MotionEvent.ACTION_MOVE -> if (!hit) pressed = false
            MotionEvent.ACTION_UP -> {
                if (pressed && hit) onContinue()
                pressed = false
            }
            MotionEvent.ACTION_CANCEL -> pressed = false
        }
        invalidate()
        return true
    }

    companion object {
        /** Seconds until the "World 2 Unlocked!" card pops in, and then the Continue button. */
        const val UNLOCK_AT = 1.6f
        const val BUTTON_AT = 2.4f
        /** Height of the cards and button together, in 1080-wide units. */
        private const val CONTENT_H = 1330f
        private const val RAYS = 14
        private const val CONFETTI = 60
    }
}
