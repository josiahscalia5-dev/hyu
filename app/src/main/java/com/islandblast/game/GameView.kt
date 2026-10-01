package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.islandblast.game.model.Level5Game
import com.islandblast.game.render.Assets
import com.islandblast.game.render.Effects
import com.islandblast.game.render.Renderer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts Level 5 full screen, edge to edge.
 *
 * The approved screen (the "stage") is scaled uniformly, never stretched:
 *  - as large as possible so the scene fills the whole display, but
 *  - never so large that any HUD element (pause, sign, timer, Goal, combo, score,
 *    coins) leaves the safe area (camera cutout, status/navigation bars, rounded
 *    corners).
 * Whatever the stage does not cover is filled by the same scene painted past its
 * edges (background_ext), so there are no bars or borders on any phone shape.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val assets: Assets) : View(context), Choreographer.FrameCallback {
    var game = Level5Game(assets.spec)
        private set
    private var effects = Effects(assets)
    private val renderer = Renderer(assets)

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private val stage = RectF()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Safe-area insets in view pixels (left, top, right, bottom). */
    private val safe = IntArray(4)

    private var lastNanos = 0L
    private var running = false

    /** When the win/lose card appeared (uptime ms); taps restart only after a short beat. */
    private var overSince = 0L

    /** The win/lose card is up and a tap will start the level again. */
    val canRestart: Boolean
        get() = game.over && overSince != 0L && SystemClock.uptimeMillis() - overSince >= RESTART_GUARD_MS

    /** True while hit flashes, shards or sparks are still on screen. */
    val effectsBusy: Boolean get() = effects.busy

    /**
     * Test hook: while true, frames keep drawing but game time stands still, so a
     * test can capture and inspect the screen without the clock running on.
     */
    var clockHeld = false

    /** Maps a stage point (reference pixels) to view pixels. */
    fun stageToView(x: Float, y: Float) = PointF(offX + x * scale, offY + y * scale)

    /** The stage's rectangle in view pixels (may extend past the view). */
    val stageRect: RectF get() = RectF(stage)

    /** The area the HUD must stay inside, in view pixels. */
    val safeRect: RectF
        get() = RectF(safe[0] + pad(), safe[1] + pad(), width - safe[2] - pad(), height - safe[3] - pad())

    /** Test hook: pretend the device reports these insets (e.g. a camera cutout). */
    fun setSafeInsetsForTest(l: Int, t: Int, r: Int, b: Int) {
        safe[0] = l; safe[1] = t; safe[2] = r; safe[3] = b
        fitStage()
        invalidate()
    }

    private fun pad() = 6f * resources.displayMetrics.density

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val (l, t, r, b) = StageFit.safeInsets(insets)
        safe[0] = l; safe[1] = t; safe[2] = r; safe[3] = b
        fitStage()
        invalidate()
        return insets
    }

    // Touch state: a short tap on the ball switches colour, any drag aims, release shoots.
    private var downX = 0f
    private var downY = 0f
    private var downOnBall = false
    private var aiming = false

    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun pauseGame() {
        if (!game.over) game.paused = true
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f)
        lastNanos = frameTimeNanos
        if (!clockHeld) game.update(min(dt, 1f / 30f))
        effects.consume(game)
        if (game.over && overSince == 0L) overSince = SystemClock.uptimeMillis()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fitStage()

    private fun fitStage() {
        val s = assets.spec
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val hud = s.hud.extent
        val safeArea = safeRect
        // Cover the screen, unless that would push the HUD out of the safe area.
        val cover = max(w / s.stageW, h / s.stageH)
        val fitHud = min(safeArea.width() / hud.w, safeArea.height() / hud.h)
        scale = min(cover, fitHud)
        offX = place((w - s.stageW * scale) / 2f, hud.l, hud.r, safeArea.left, safeArea.right)
        offY = place((h - s.stageH * scale) / 2f, hud.t, hud.b, safeArea.top, safeArea.bottom)
        stage.set(offX, offY, offX + s.stageW * scale, offY + s.stageH * scale)
    }

    /** Offset nearest [centred] that keeps stage span [a, b] inside screen span [lo, hi]. */
    private fun place(centred: Float, a: Float, b: Float, lo: Float, hi: Float): Float {
        val min = lo - a * scale
        val max = hi - b * scale
        return if (min <= max) centred.coerceIn(min, max) else (min + max) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val s = assets.spec
        val ext = RectF(
            stage.left - s.extMarginX * scale, stage.top - s.extMarginY * scale,
            stage.right + s.extMarginX * scale, stage.bottom + s.extMarginY * scale,
        )
        if (ext.left > 0f || ext.top > 0f || ext.right < width || ext.bottom < height) {
            // Only on extreme shapes: a soft blur of the scene behind the painted margins.
            val cover = max(width / ext.width(), height / ext.height())
            val cw = ext.width() * cover
            val ch = ext.height() * cover
            canvas.drawBitmap(assets.backgroundBlur, null,
                RectF((width - cw) / 2f, (height - ch) / 2f, (width + cw) / 2f, (height + ch) / 2f), bgPaint)
        }
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        renderer.draw(canvas, game, effects)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - offX) / scale
        val y = (e.y - offY) / scale
        val s = assets.spec
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                aiming = false
                if (game.over) {
                    if (canRestart) restart()
                    return true
                }
                if (game.paused) {
                    game.paused = false
                    return true
                }
                val p = s.hud.pause
                if (x in p.l - 10f..p.r + 10f && y in p.t - 10f..p.b + 10f) {
                    game.paused = true
                    return true
                }
                downOnBall = hypot(x - s.ballX, y - s.ballY) < s.ballRadius * 1.15f
                if (!downOnBall) {
                    aiming = true
                    game.aimAt(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (downOnBall && hypot(x - downX, y - downY) > 28f) {
                    downOnBall = false
                    aiming = true
                }
                if (aiming) game.aimAt(x, y)
            }
            MotionEvent.ACTION_UP -> {
                if (downOnBall) game.switchColor()
                else if (aiming) game.shoot()
                downOnBall = false
                aiming = false
            }
            MotionEvent.ACTION_CANCEL -> {
                downOnBall = false
                aiming = false
            }
        }
        return true
    }

    private fun restart() {
        game = Level5Game(assets.spec)
        effects.clear()
        effects = Effects(assets)
        renderer.reset()
        overSince = 0L
    }

    companion object {
        private const val RESTART_GUARD_MS = 700L
    }
}
