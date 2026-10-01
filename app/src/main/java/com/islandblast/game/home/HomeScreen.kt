package com.islandblast.game.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
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
import com.islandblast.game.model.Box
import com.islandblast.game.ui.UiKit
import kotlin.math.min
import kotlin.math.sin

/** The title screen: PLAY (or the Levels tile) opens the World 1 map. */
class HomeScreen(private val flow: GameFlow) : Screen {
    val home: HomeView = HomeView(flow.context, AssetCache.get("home") { HomeAssets(flow.context.assets) }, flow.ui) { target ->
        when (target) {
            "play", "levels" -> flow.openMap()
            else -> home.toast("Coming soon!")
        }
    }
    override val view: View get() = home

    override fun start() {
        home.loop.start()
        flow.preloadMap()
    }

    override fun stop() = home.loop.stop()
    override fun onBack(): Boolean = false
}

/**
 * Draws the approved home screen on any portrait phone with nothing cropped:
 *  - the top bar is pinned to the top of the safe area, the nav bar to the bottom;
 *  - the logo, character and PLAY button (with the scene behind them) are scaled to
 *    fill the space between, never wider than the screen;
 *  - the scene is painted past its edges, so taller phones show more sky.
 */
@SuppressLint("ViewConstructor")
class HomeView(
    context: Context,
    private val assets: HomeAssets,
    private val ui: UiKit,
    private val onTap: (String) -> Unit,
) : View(context) {
    private val spec = assets.spec
    val loop = FrameLoop { dt ->
        time += dt
        invalidate()
    }
    private var time = 0f
    private val safe = SafeArea()

    // Layout (view pixels): bar scale and offsets, hero scale and offset.
    var barScale = 1f
        private set
    private var topY = 0f
    private var navY = 0f
    var heroScale = 1f
        private set
    private var heroX = 0f
    private var heroY = 0f

    private var pressed: String? = null
    private var toastText = ""
    private var toastAt = -10f

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val fade = Paint()
    private val src = Rect()
    private val dst = RectF()

    fun toast(msg: String) {
        toastText = msg
        toastAt = time
        invalidate()
    }

    /** Test hook: pretend the device reports these insets (e.g. a camera cutout). */
    fun setSafeInsetsForTest(l: Int, t: Int, r: Int, b: Int) {
        safe.set(l, t, r, b)
        layoutParts()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        safe.apply(insets)
        layoutParts()
        invalidate()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutParts()

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val area = safe.rect(width, height, 0f)
        val pad = 0.012f * h
        // Bars span the safe width.
        barScale = area.width() / spec.artW
        val u = barScale
        topY = area.top + pad * 0.6f - spec.topItemsExtent.t * u
        val topBottom = topY + spec.topItemsExtent.b * u
        // The nav's labels sit at the bottom of its art: keep them inside the safe area.
        navY = area.bottom - NAV_LIFT * h - spec.nav.b * u
        val navTop = navY + spec.navTilesTop * u
        // Logo, character and PLAY fill what is left, never wider than the screen.
        val hero = spec.hero
        val room = (navTop - pad) - (topBottom + pad)
        heroScale = min(min(area.width() * 0.97f / hero.w, room / hero.h), u * MAX_ZOOM)
        heroX = area.centerX() - hero.cx * heroScale
        // PLAY sits just above the nav; any spare height is sky above the logo.
        heroY = navTop - pad - hero.b * heroScale
    }

    /** Art point of the hero layer (logo, character, PLAY, scene) to view pixels. */
    fun heroToView(x: Float, y: Float) = PointF(heroX + x * heroScale, heroY + y * heroScale)

    /** Art point of the nav bar to view pixels. */
    fun navToView(x: Float, y: Float) = PointF(x * barScale + safe.insets[0], navY + y * barScale)

    /** Art point of the top bar to view pixels. */
    fun topToView(x: Float, y: Float) = PointF(x * barScale + safe.insets[0], topY + y * barScale)

    /** View-pixel boxes of the tappable parts, by name. */
    fun targets(): Map<String, RectF> {
        val out = LinkedHashMap<String, RectF>()
        out["play"] = heroBox(spec.playShape)
        for ((k, b) in spec.navTiles) {
            val a = navToView(b.l, b.t)
            out[k] = RectF(a.x, a.y, navToView(b.r, b.b).x, height.toFloat())
        }
        for ((k, b) in spec.topItems) out[k] = RectF(topToView(b.l, b.t).x, topToView(b.l, b.t).y,
            topToView(b.r, b.b).x, topToView(b.r, b.b).y)
        return out
    }

    private fun heroBox(b: Box): RectF {
        val a = heroToView(b.l, b.t)
        val c = heroToView(b.r, b.b)
        return RectF(a.x, a.y, c.x, c.y)
    }

    /** Everything on screen that must not be cropped, in view pixels (tests check it). */
    fun mustShow(): Map<String, RectF> = linkedMapOf(
        "logo" to heroBox(Box(spec.hero.l, spec.logo.t, spec.hero.r, spec.logo.b)),
        "play" to heroBox(spec.playShape),
        "topBar" to RectF(topToView(spec.topItemsExtent.l, spec.topItemsExtent.t).x,
            topToView(0f, spec.topItemsExtent.t).y, topToView(spec.topItemsExtent.r, 0f).x,
            topToView(0f, spec.topItemsExtent.b).y),
        "nav" to RectF(navToView(spec.navTiles.values.minOf { it.l }, 0f).x, navToView(0f, spec.navTilesTop).y,
            navToView(spec.navTiles.values.maxOf { it.r }, 0f).x, navToView(0f, spec.nav.b).y),
    )

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val s = heroScale
        // Scene: on extreme shapes, a soft blur shows where the painted margins end.
        dst.set(heroX - spec.extLeft * s, heroY - spec.extTop * s, heroX + (spec.artW + spec.extRight) * s,
            heroY + (spec.artH + spec.extBottom) * s)
        if (dst.left > 0f || dst.top > 0f || dst.right < w || dst.bottom < h) {
            c.drawBitmap(assets.backgroundBlur, null, RectF(0f, 0f, w, h), bmp)
        }
        c.drawBitmap(assets.background, null, dst, bmp)
        drawLogoShine(c)
        drawSparkles(c)
        drawPlay(c)
        drawBars(c)
        val tk = time - toastAt
        if (tk in 0f..1.6f) {
            val k = UiKit.unit(width, height)
            val alpha = min(1f, min(tk / 0.15f, (1.6f - tk) / 0.3f))
            ui.toast(c, toastText, w / 2f, navToView(0f, spec.navTilesTop).y - 90f * k, alpha, k)
        }
        // Fade in from black when the screen opens.
        if (time < FADE_IN) {
            fade.color = ((255 * (1f - time / FADE_IN)).toInt() shl 24)
            c.drawRect(0f, 0f, w, h, fade)
        }
    }

    private fun drawBars(c: Canvas) {
        val u = barScale
        val x0 = safe.insets[0].toFloat()
        // Top bar.
        val tb = spec.topBar
        dst.set(x0 + tb.l * u, topY + tb.t * u, x0 + tb.r * u, topY + tb.b * u)
        c.drawBitmap(assets.topBar, null, dst, bmp)
        // Nav bar, with its last row stretched down to the bottom edge of the screen.
        val nb = spec.nav
        val pressedTile = pressed?.let { spec.navTiles[it] }
        dst.set(x0 + nb.l * u, navY + nb.t * u, x0 + nb.r * u, navY + nb.b * u)
        c.drawBitmap(assets.nav, null, dst, bmp)
        if (dst.bottom < height) {
            src.set(0, assets.nav.height - 2, assets.nav.width, assets.nav.height)
            c.drawBitmap(assets.nav, src, RectF(dst.left, dst.bottom - 1f, dst.right, height.toFloat()), bmp)
        }
        if (pressedTile != null) {
            val a = navToView(pressedTile.l, pressedTile.t)
            val b = navToView(pressedTile.r, pressedTile.b)
            fade.color = 0x33000000
            c.drawRoundRect(a.x, a.y, b.x, height.toFloat(), 28f * u, 28f * u, fade)
        }
    }

    private fun drawPlay(c: Canvas) {
        val pb = spec.play
        val pulse = 1f + 0.022f * sin(time * 3.4f)
        val press = if (pressed == "play") 0.94f else 1f
        val sc = pulse * press
        val center = heroToView(spec.playShape.cx, spec.playShape.cy)
        c.save()
        c.scale(sc, sc, center.x, center.y)
        val box = heroBox(pb)
        c.drawBitmap(assets.play, null, box, bmp)
        // A glint sweeps across the button every few seconds.
        val k = ((time % 3.2f) - 0.6f) / 0.9f
        if (k in 0f..1f) {
            c.saveLayer(box, null)
            c.drawBitmap(assets.play, null, box, bmp)
            val x = box.left - box.height() + (box.width() + box.height() * 2f) * k
            shine.shader = LinearGradient(x - box.height() * 0.5f, box.top, x + box.height() * 0.5f, box.bottom,
                intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRect(box, shine)
            c.restore()
        }
        c.restore()
    }

    private fun drawLogoShine(c: Canvas) {
        val k = (((time + 1.4f) % 4.5f) - 0.4f) / 1.1f
        if (k !in 0f..1f) return
        val lb = heroBox(spec.logo)
        c.saveLayer(lb, null)
        c.drawBitmap(assets.logoMask, null, lb, bmp)
        val x = lb.left - lb.width() * 0.3f + lb.width() * 1.6f * k
        shine.shader = LinearGradient(x - lb.width() * 0.12f, lb.top, x + lb.width() * 0.12f, lb.bottom,
            intArrayOf(0x00FFFFFF, 0x70FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(lb, shine)
        c.restore()
    }

    /** Twinkles around the logo, like light catching the lettering. */
    private fun drawSparkles(c: Canvas) {
        val spots = SPARKLES
        for (i in spots.indices step 2) {
            val ph = (time * 0.8f + i * 0.37f) % 2.2f
            if (ph > 0.6f) continue
            val a = sin(ph / 0.6f * Math.PI.toFloat())
            val p = heroToView(spots[i], spots[i + 1])
            val r = 26f * heroScale * a
            add.color = ((200 * a).toInt() shl 24) or 0xFFFFFF
            c.drawOval(p.x - r, p.y - r * 0.12f, p.x + r, p.y + r * 0.12f, add)
            c.drawOval(p.x - r * 0.12f, p.y - r, p.x + r * 0.12f, p.y + r, add)
            c.drawCircle(p.x, p.y, r * 0.18f, add)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = targets().entries.firstOrNull { it.value.contains(e.x, e.y) }?.key
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = hit
            MotionEvent.ACTION_MOVE -> if (hit != pressed) pressed = null
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                if (p != null && p == hit && time > FADE_IN) onTap(p)
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        invalidate()
        return true
    }

    companion object {
        /** The logo/character/PLAY may grow this much past the bars' scale to fill tall screens. */
        const val MAX_ZOOM = 1.28f
        const val FADE_IN = 0.3f
        /** The nav's labels sit this far (share of screen height) above the bottom edge; its tiles run on below. */
        const val NAV_LIFT = 0.009f
        private val SPARKLES = floatArrayOf(205f, 262f, 780f, 220f, 905f, 470f, 300f, 600f, 620f, 380f)
    }
}
